package dev.gdirect.lite.diagnostics;

import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.DirectRoutes;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Bounded, structured local diagnostics. No API accepts messages, URLs, headers or file names. */
public final class DiagnosticLog {
    public static final int MAX_ENTRIES=512,MAX_BYTES=128*1024;
    public enum Event {
        APP_START,MODE_GOOGLE_ONLY,MODE_RELAY_ALLOWED,MODE_SYSTEM,NETWORK_CHANGED,NETWORK_LOST,ROUTES_RESET,IPV6_STATUS,RANGE_IPV6_READY,DNS_A_READY,DNS_AAAA_READY,
        RANGE_BEGIN,RANGE_READY,RANGE_BOOTSTRAP,RANGE_GOOG_HTTP,RANGE_CLOUD_HTTP,RANGE_FETCH_FAILED,DNS_BEGIN,DNS_READY,DNS_HTTP,DNS_FAILED,SCAN_BEGIN,SCAN_READY,
        SELECT_BEGIN,SELECT_OK,SELECT_FAILED,CACHE_HIT,PREFERRED_BEGIN,PREFERRED_FALLBACK,DISCOVERY_BEGIN,
        PROBE_OK,PROBE_FAILED,PROBE_HTTP,
        REQUEST_READ_BEGIN,REQUEST_WRITE_BEGIN,REQUEST_HEADERS,REQUEST_RESPONSE,REQUEST_RETRY,REQUEST_FAILED,REQUEST_CANCELLED,BODY_FAILED,
        CHECK_BEGIN,CHECK_CANCEL,CHECK_OK,CHECK_FAILED,
        AUTH_BEGIN,AUTH_CALLBACK,AUTH_OK,AUTH_FAILED,ACCOUNT_DISCONNECTED,
        AUTH_RESTORE_BEGIN,AUTH_RESTORE_OK,AUTH_RESTORE_REQUIRED,AUTH_RESTORE_FAILED,AUTH_RESTORE_RETRY,
        LIST_BEGIN,LIST_READY,LIST_DISPLAYED,LIST_FAILED,BROWSE_AUTOCONNECT,
        UPLOAD_BEGIN,DOWNLOAD_BEGIN,TRANSFER_OK,TRANSFER_FAILED,TRANSFER_CANCELLED
    }
    public enum Target { NONE,DRIVE_API,DRIVE_CONTENT,GOOGLE_DNS,GOOGLE_RANGES,OTHER }
    public enum Failure { NONE,AUTHORIZATION,CERTIFICATE,TLS,TIMEOUT,DNS,NETWORK,CANCELLED,IO,INTERNAL }
    private static final DiagnosticLog GLOBAL=new DiagnosticLog();
    public static DiagnosticLog get(){return GLOBAL;}
    private final ArrayDeque<String> entries=new ArrayDeque<>();
    private final AtomicLong operations=new AtomicLong();
    private int bytes;
    private long clearVersion;
    private volatile Runnable changed=()->{};
    public long operation(){return operations.incrementAndGet();}
    public static long elapsed(long start){return Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
    public static Target target(String host) {
        if(host==null)return Target.NONE;
        if(host.equals("www.googleapis.com"))return Target.DRIVE_API;
        if(host.equals("drive.usercontent.google.com")||host.endsWith(".googleusercontent.com"))return Target.DRIVE_CONTENT;
        if(host.equals("dns.google"))return Target.GOOGLE_DNS;
        if(host.equals("www.gstatic.com"))return Target.GOOGLE_RANGES;
        return Target.OTHER;
    }
    public static Failure failure(Throwable error) {
        if(error==null)return Failure.NONE;
        if(error instanceof DriveSession.AuthorizationRequired)return Failure.AUTHORIZATION;
        Throwable current=error;
        for(int i=0;i<6&&current!=null;i++,current=current.getCause()) {
            if(current instanceof java.security.cert.CertificateException||current instanceof javax.net.ssl.SSLPeerUnverifiedException)return Failure.CERTIFICATE;
            if(current instanceof javax.net.ssl.SSLException)return Failure.TLS;
            if(current instanceof java.net.SocketTimeoutException)return Failure.TIMEOUT;
            if(current instanceof java.net.UnknownHostException)return Failure.DNS;
            if(current instanceof java.net.SocketException)return Failure.NETWORK;
            if(current instanceof java.io.InterruptedIOException)return Failure.CANCELLED;
        }
        return error instanceof IOException?Failure.IO:Failure.INTERNAL;
    }
    public void event(Event event){record(event,0,null,null,null,0,0,null);}
    public void record(Event event,long operation,String host,String ip,DirectRoutes.TlsMode tls,long millis,int code,Throwable error) {
        try {
            String line=encode(System.currentTimeMillis(),event,operation,target(host),ip,tls,millis,code,failure(error));
            synchronized(this){append(line);}
            changed.run();
        }catch(RuntimeException ignored){/* Diagnostics must never break a request. */}
    }
    private static String encode(long time,Event event,long operation,Target target,String ip,DirectRoutes.TlsMode tls,long millis,int code,Failure error) {
        try{return new JSONObject().put("v",1).put("time",time).put("event",event.name()).put("op",Math.max(0,operation))
            .put("target",target.name()).put("ip",dev.gdirect.lite.network.IpLiteral.publicAddress(ip)?dev.gdirect.lite.network.IpLiteral.canonical(ip):"").put("tls",tls==null?"":tls.name())
            .put("ms",Math.max(0,Math.min(millis,86400000))).put("code",code).put("error",error.name()).toString();}
        catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    private void append(String line) {
        entries.addLast(line);bytes+=line.length()+1;
        while(entries.size()>MAX_ENTRIES||bytes>MAX_BYTES)bytes-=entries.removeFirst().length()+1;
    }
    public synchronized String encoded(){return entries.isEmpty()?"":String.join("\n",entries)+"\n";}
    public synchronized int size(){return entries.size();}
    public synchronized long clearVersion(){return clearVersion;}
    public void onChanged(Runnable callback){changed=callback;}
    public void clear(){synchronized(this){entries.clear();bytes=0;clearVersion++;}changed.run();}
    public void restore(String text,long expectedClearVersion) {
        if(text==null||text.length()>MAX_BYTES)return;
        ArrayDeque<String> restored=new ArrayDeque<>();
        for(String line:text.split("\n")) {
            try {
                JSONObject o=new JSONObject(line);if(o.getInt("v")!=1)continue;
                String tls=o.optString("tls","");
                restored.addLast(encode(o.getLong("time"),Event.valueOf(o.getString("event")),o.getLong("op"),Target.valueOf(o.getString("target")),
                    o.optString("ip",""),tls.isEmpty()?null:DirectRoutes.TlsMode.valueOf(tls),o.getLong("ms"),o.getInt("code"),Failure.valueOf(o.getString("error"))));
            }catch(org.json.JSONException|IllegalArgumentException ignored){}
        }
        synchronized(this) {
            if(clearVersion!=expectedClearVersion)return;
            ArrayDeque<String> live=new ArrayDeque<>(entries);entries.clear();bytes=0;
            for(String line:restored)append(line);for(String line:live)append(line);
        }
    }
    public String report() {
        String snapshot=encoded();
        StringBuilder out=new StringBuilder("UTC timestamps; op is process-local. Target is a service category, IP is a remote candidate.\n"
            +"Stored entries may belong to earlier builds/processes. APP_START code is versionCode (0 in older logs).\n"
            +"IPV6_STATUS code: 0=unknown, 1=offline, 2=no global address, 3=no default route, 4=dual-stack eligible.\n"
            +"RANGE_IPV6_READY code = allowed IPv6 interval count; DNS_A_READY/DNS_AAAA_READY code = filtered unique address count.\n"
            +"BROWSE_AUTOCONNECT code: 1=restored session, 2=network change, 3=bounded retry, 4=completed route check.\n"
            +"ms = elapsed milliseconds. code = HTTP/Google status, or candidate count for DNS_READY/SCAN_READY.\n"
            +"No account, file names/IDs, URLs, queries, headers, tokens or exception messages are recorded.\nPROBE_HTTP code=401 for DRIVE_API is an expected credential-free probe, not a failed login.\n\n");
        for(String line:snapshot.split("\n"))try {
            JSONObject o=new JSONObject(line);
            out.append(java.time.Instant.ofEpochMilli(o.getLong("time"))).append(" op=").append(o.getLong("op"))
                .append(' ').append(o.getString("event")).append(" target=").append(o.getString("target"));
            if(!o.getString("ip").isEmpty())out.append(" ip=").append(o.getString("ip"));
            if(!o.getString("tls").isEmpty())out.append(" tls=").append(o.getString("tls"));
            out.append(" ms=").append(o.getLong("ms")).append(" code=").append(o.getInt("code"))
                .append(" error=").append(o.getString("error")).append('\n');
        }catch(org.json.JSONException ignored){}
        return out.toString();
    }
}
