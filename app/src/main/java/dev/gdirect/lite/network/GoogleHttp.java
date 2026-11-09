package dev.gdirect.lite.network;

import dev.gdirect.lite.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded metadata/chunk adapter. Only GET may retry; writes never follow redirects or blindly replay. */
public final class GoogleHttp {
    public interface Clients { OkHttpClient get() throws IOException; }
    public interface Guard { void check() throws IOException; }
    public interface Status {
        default void started(){}
        default void response(int code){}
        default void failed(Throwable error){}
        default void cancelled(){}
    }
    public static OkHttpClient direct(OkHttpClient trusted, DirectRoutes.Route route) throws IOException {
        final InetAddress address;
        try{address=InetAddress.getByAddress(IpLiteral.bytes(route.ip));}
        catch(IllegalArgumentException invalid){throw new java.net.UnknownHostException("Invalid numeric route address");}
        OkHttpClient.Builder builder = trusted.newBuilder()
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .dns(host -> {
                if (!host.equalsIgnoreCase(route.tls==DirectRoutes.TlsMode.GOOGLE_FRONT?"g.cn":route.host)) throw new java.net.UnknownHostException("Unmapped direct host");
                return Collections.singletonList(address);
            });
        if(route.tls==DirectRoutes.TlsMode.GOOGLE_FRONT) {
            // Keep the logical origin separate from the validated Google TLS identity.
            // HTTP/1.1 avoids cross-origin HTTP/2 connection coalescing and authority ambiguity.
            builder.protocols(Collections.singletonList(Protocol.HTTP_1_1)).addInterceptor(chain->{
                Request logical=chain.request();
                if(!logical.url().isHttps()||!logical.url().host().equals(route.host)||!(DirectNet.googleHost(route.host)||route.host.equals("oauth2.googleapis.com"))
                    ||!logical.url().username().isEmpty()||!logical.url().password().isEmpty())throw new IOException("不允许的前端请求来源");
                if(!route.host.equals("www.googleapis.com")&&(logical.header("Authorization")!=null||logical.header("X-Goog-Drive-Resource-Keys")!=null))
                    throw new IOException("已阻止向内容服务器发送账户凭据");
                String authority=route.host+(logical.url().port()==443?"":":"+logical.url().port());
                return chain.proceed(logical.newBuilder().url(logical.url().newBuilder().host("g.cn").build()).header("Host",authority).build());
            });
        }
        if (route.tls == DirectRoutes.TlsMode.NO_SNI) {
            builder.sslSocketFactory(new NoSniFactory(trusted.sslSocketFactory()), trusted.x509TrustManager())
                .connectionSpecs(Collections.singletonList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .supportsTlsExtensions(false).build()))
                .protocols(Collections.singletonList(Protocol.HTTP_1_1));
        }
        // Default verifier checks g.cn for GOOGLE_FRONT, otherwise the original host (also NO_SNI).
        return builder.build();
    }

    /** Stable client/DNS identity permits connection reuse across data requests. */
    public static final class DirectClients {
        private final OkHttpClient trusted;
        private final java.util.LinkedHashMap<String,OkHttpClient> clients = new java.util.LinkedHashMap<>(32,0.75f,true);
        public DirectClients(OkHttpClient trusted) { this.trusted=trusted; }
        public synchronized OkHttpClient get(DirectRoutes.Route route) throws IOException {
            String key=route.host+"/"+route.key();
            OkHttpClient client=clients.get(key);
            if(client==null) {
                client=direct(trusted,route); clients.put(key,client);
                if(clients.size()>32)clients.remove(clients.keySet().iterator().next());
            }
            return client;
        }
        public synchronized void clear() { clients.clear(); trusted.connectionPool().evictAll(); }
    }

    public static HttpsURLConnection open(URL url, Clients clients, Runnable failed) {
        return open(url,clients,failed, () -> { });
    }
    public static HttpsURLConnection open(URL url, Clients clients, Runnable failed, Guard guard) {
        return open(url,clients,failed,guard,new Status(){});
    }
    public static HttpsURLConnection open(URL url,Clients clients,Runnable failed,Guard guard,Status status){
        return new GetConnection(url,clients,failed,guard,status);
    }
    public static final class GetConnection extends HttpsURLConnection {
        private final long logOperation=DiagnosticLog.get().operation();
        private long requestStart;
        private final Clients clients;
        private final Runnable failed;
        private final Request.Builder request;
        private final Guard guard;
        private final Status status;
        private final java.util.concurrent.atomic.AtomicBoolean statusStarted=new java.util.concurrent.atomic.AtomicBoolean();
        private final ByteArrayOutputStream payload=new ByteArrayOutputStream();
        private volatile Response response;
        private volatile Call call;
        private volatile boolean closed;
        private volatile Runnable closedHook;
        private final java.util.concurrent.atomic.AtomicBoolean hookCalled=new java.util.concurrent.atomic.AtomicBoolean();
        public void onClose(Runnable hook){closedHook=hook;notifyClosed();}
        private void notifyClosed(){Runnable hook=closedHook;if(closed&&hook!=null&&hookCalled.compareAndSet(false,true))hook.run();}
        public GetConnection(URL url, Clients clients, Runnable failed, Guard guard) {
            this(url,clients,failed,guard,new Status(){});
        }
        public GetConnection(URL url,Clients clients,Runnable failed,Guard guard,Status status){
            super(url); this.clients = clients; this.failed = failed;
            this.guard = guard;this.status=status;
            request = new Request.Builder().url(url);
            setConnectTimeout(10000); setReadTimeout(20000);
        }
        @Override public void connect() throws IOException {
            if(!closed&&response!=null)return;
            if(statusStarted.compareAndSet(false,true))notifyStatus(status::started);
            requestStart=System.nanoTime();
            DiagnosticLog.get().record("GET".equals(method)?DiagnosticLog.Event.REQUEST_READ_BEGIN:DiagnosticLog.Event.REQUEST_WRITE_BEGIN,logOperation,url.getHost(),null,null,0,0,null);
            try{connectInternal();notifyStatus(()->status.response(response.code()));DiagnosticLog.get().record(DiagnosticLog.Event.REQUEST_RESPONSE,logOperation,url.getHost(),null,null,DiagnosticLog.elapsed(requestStart),response.code(),null);}
            catch(IOException|RuntimeException error){reportStatus(error);DiagnosticLog.get().record(isCancellation(error)?DiagnosticLog.Event.REQUEST_CANCELLED:DiagnosticLog.Event.REQUEST_FAILED,logOperation,url.getHost(),null,null,DiagnosticLog.elapsed(requestStart),0,error);throw error;}
        }
        private void notifyStatus(Runnable callback){try{callback.run();}catch(RuntimeException ignored){}}
        private boolean isCancellation(Throwable error){
            return closed||Thread.currentThread().isInterrupted()
                ||(error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException)
                    &&(call==null||!call.isCanceled()));
        }
        private void reportStatus(Throwable error){notifyStatus(isCancellation(error)?status::cancelled:()->status.failed(error));}
        private IOException callFailure(IOException error){
            // OkHttp's whole-call deadline is InterruptedIOException, but it is a timeout,
            // not user cancellation. Keep retry/status/log classification consistent.
            if(!closed&&!Thread.currentThread().isInterrupted()&&call!=null&&call.isCanceled()
                &&error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException)){
                java.net.SocketTimeoutException timeout=new java.net.SocketTimeoutException("Google request deadline exceeded");
                timeout.initCause(error);return timeout;
            }
            return error;
        }
        private IOException bodyFailure(IOException original){
            IOException error=callFailure(original);
            boolean cancelled=isCancellation(error);
            if(!cancelled)try{guard.check();}catch(IOException stopped){cancelled=true;error=stopped;}
            if(cancelled)notifyStatus(status::cancelled);
            else {reportStatus(error);failed.run();}
            DiagnosticLog.get().record(DiagnosticLog.Event.BODY_FAILED,logOperation,url.getHost(),null,null,
                DiagnosticLog.elapsed(requestStart),0,cancelled?new java.io.InterruptedIOException():error);
            return error;
        }
        private static int boundedTimeout(int configured,int maximum){return configured==0?maximum:Math.min(configured,maximum);}
        private void connectInternal()throws IOException {
            if (closed) throw new IOException("连接已关闭");
            if (response != null) return;
            if ("GET".equals(method) && doOutput) throw new IOException("GET cannot carry a body");
            // At most two attempts, and only before a response. No redirects or mutations are replayed.
            int limit="GET".equals(method)?2:1;
            for (int attempt = 0; attempt < limit; attempt++) {
                guard.check();
                OkHttpClient source=clients.get();Request logical=request.build();
                boolean metadata="GET".equals(method)&&logical.url().host().equals("www.googleapis.com")
                    &&logical.url().encodedPath().startsWith("/drive/v3/")
                    &&!logical.url().encodedPath().endsWith("/export")
                    &&!logical.url().queryParameterValues("alt").contains("media")
                    &&logical.header("Range")==null;
                OkHttpClient client = source.newBuilder()
                    .connectTimeout(metadata?boundedTimeout(getConnectTimeout(),5000):getConnectTimeout(),TimeUnit.MILLISECONDS)
                    .readTimeout(metadata?boundedTimeout(getReadTimeout(),8000):getReadTimeout(),TimeUnit.MILLISECONDS)
                    .callTimeout(metadata?boundedTimeout(source.callTimeoutMillis(),12000):source.callTimeoutMillis(),TimeUnit.MILLISECONDS)
                    .writeTimeout(getReadTimeout(),TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
                    .followRedirects(false).followSslRedirects(false)
                    .addNetworkInterceptor(chain->{
                        // TLS/discovery may take seconds. Recheck immediately before HTTP headers/body.
                        if(closed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("连接已取消");
                        guard.check();DiagnosticLog.get().record(DiagnosticLog.Event.REQUEST_HEADERS,logOperation,url.getHost(),null,null,DiagnosticLog.elapsed(requestStart),0,null);return chain.proceed(chain.request());
                    }).build();
                guard.check();
                okhttp3.RequestBody body=("POST".equals(method)||"PATCH".equals(method)||"PUT".equals(method))
                    ?okhttp3.RequestBody.create(payload.toByteArray(),okhttp3.MediaType.parse(getRequestProperty("Content-Type")==null?"application/json; charset=utf-8":getRequestProperty("Content-Type"))):null;
                call = client.newCall(request.method(method,body).build());
                if (closed || Thread.currentThread().isInterrupted()) { call.cancel(); throw new java.io.InterruptedIOException("连接已取消"); }
                try {
                    response = call.execute(); connected = true; return;
                } catch (IOException original) {
                    IOException error=callFailure(original);
                    if(isCancellation(error))throw error;
                    // Account/policy cancellation does not mean the endpoint itself failed.
                    guard.check();failed.run();
                    if (attempt + 1 >= limit) throw error;
                    DiagnosticLog.get().record(DiagnosticLog.Event.REQUEST_RETRY,logOperation,url.getHost(),null,null,DiagnosticLog.elapsed(requestStart),0,error);
                }
            }
        }
        @Override public void disconnect() {
            closed = true;
            try{if (call != null) call.cancel();if (response != null) response.close();}finally{notifyClosed();}
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestMethod(String value) throws java.net.ProtocolException {
            if(connected || !(value.equals("GET")||value.equals("POST")||value.equals("PATCH")||value.equals("PUT")||value.equals("DELETE")))
                throw new java.net.ProtocolException("Unsupported request method");
            method=value;
        }
        @Override public String getRequestProperty(String name) { return request.build().header(name); }
        @Override public OutputStream getOutputStream() throws IOException {
            if(connected || !doOutput) throw new IOException("Output is not available");
            return new OutputStream() {
                public void write(int b)throws IOException { if(payload.size()>=1024*1024)throw new IOException("Request metadata too large");payload.write(b); }
                public void write(byte[] b,int offset,int length)throws IOException { if(length>1024*1024-payload.size())throw new IOException("Request metadata too large");payload.write(b,offset,length); }
            };
        }
        @Override public void setRequestProperty(String key, String value) {
            if (connected) throw new IllegalStateException("Already connected");
            request.header(key, value);
        }
        @Override public int getResponseCode() throws IOException { connect(); return response.code(); }
        @Override public String getResponseMessage() throws IOException { connect(); return response.message(); }
        @Override public String getHeaderField(String name) {
            try { connect(); return name == null ? "HTTP/1.1 " + response.code() : response.header(name); }
            catch (IOException error) { return null; }
        }
        @Override public Map<String,List<String>> getHeaderFields() {
            try { connect(); return response.headers().toMultimap(); }
            catch (IOException error) { return Collections.emptyMap(); }
        }
        @Override public InputStream getInputStream() throws IOException {
            connect();
            if (response.code() >= 400) throw new IOException("HTTP " + response.code());
            return new java.io.FilterInputStream(response.body().byteStream()) {
                @Override public int read() throws IOException {
                    try { return in.read(); } catch (IOException error) { throw bodyFailure(error); }
                }
                @Override public int read(byte[] data,int offset,int count) throws IOException {
                    try { return in.read(data,offset,count); } catch (IOException error) { throw bodyFailure(error); }
                }
            };
        }
        @Override public InputStream getErrorStream() {
            try { connect(); return response.code() >= 400 ? response.body().byteStream() : null; }
            catch (IOException error) { return null; }
        }
        @Override public String getCipherSuite() { return response.handshake().cipherSuite().javaName(); }
        @Override public Certificate[] getLocalCertificates() { return response.handshake().localCertificates().toArray(new Certificate[0]); }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return response.handshake().peerCertificates().toArray(new Certificate[0]);
        }
    }

    /** Changes the TLS extension only. Chain trust and real-URL hostname validation stay enabled. */
    private static final class NoSniFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        NoSniFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket configure(Socket socket) {
            SSLSocket tls = (SSLSocket) socket;
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setServerNames(Collections.emptyList());
            tls.setSSLParameters(parameters);
            return tls;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return configure(delegate.createSocket()); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException { // Android Conscrypt can ignore an empty serverNames list and retain the
            // original peer hostname. Use the already-connected numeric peer so it
            // never learns a DNS SNI name. OkHttp still verifies the canonical URL
            // hostname against the certificate before sending any HTTP headers.
            return configure(delegate.createSocket(s,s.getInetAddress().getHostAddress(),p,close)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return configure(delegate.createSocket(InetAddress.getByName(h),p)); }
        @Override public Socket createSocket(String h, int p, InetAddress local, int lp) throws IOException { return configure(delegate.createSocket(InetAddress.getByName(h),p,local,lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return configure(delegate.createSocket(h,p)); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress local, int lp) throws IOException { return configure(delegate.createSocket(h,p,local,lp)); }
    }
    private GoogleHttp() { }
}
