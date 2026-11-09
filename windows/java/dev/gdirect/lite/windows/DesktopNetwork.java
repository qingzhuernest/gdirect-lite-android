package dev.gdirect.lite.windows;

import dev.gdirect.lite.network.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import okhttp3.*;
import org.json.*;

/** Windows platform composition. Protocol/TLS/discovery remain the app's Java classes. */
public final class DesktopNetwork {
    private final OkHttpClient trusted=GoogleTrust.clientBuilder().proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor(GoogleCertificatePolicy.CHECK).build();
    private final GoogleHttp.DirectClients clients=new GoogleHttp.DirectClients(trusted);
    private final GoogleBootstrap bootstrap;
    private GoogleAddressSpace space;
    private boolean prepared;
    private final GoogleRangeCatalog catalog;
    private final DirectRoutes routes;
    private final Map<String,List<DirectRoutes.Route>> cache=new HashMap<>();
    private final Map<String,Long> normalVerified=new HashMap<>();
    private final Set<String> normalFailed=new HashSet<>();
    public DesktopNetwork() throws IOException {
        bootstrap=GoogleBootstrap.bundled();space=bootstrap.subset;
        // Catalog is public, process-local only. Account material never enters it.
        catalog=new GoogleRangeCatalog(new GoogleRangeCatalog.Store(){
            private String value=""; public String read(){return value;} public void write(String value){this.value=value;}
        }, name -> GoogleRangeCatalog.download(trusted,name));
        routes=new DirectRoutes(new DirectRoutes.Candidates(){
            public List<String> get(String host)throws IOException {
                prepare(); LinkedHashSet<String> found=new LinkedHashSet<>(bootstrap.candidates(space));
                for(InetAddress address:InetAddress.getAllByName(host)) if(space.contains(address.getHostAddress()))found.add(address.getHostAddress());
                return new ArrayList<>(found);
            }
            public List<String> expand(String host,List<String> observed)throws IOException {return space.discoveryCandidates(observed,512,new java.security.SecureRandom());}
        },new DirectRoutes.Probe(){
            public void connect(String ip)throws IOException {try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(ip,443),1500);}}
            public void check(DirectRoutes.Route route)throws IOException {probe(GoogleHttp.direct(trusted,route),route.host);}
        },new DirectRoutes.Store(){
            public DirectRoutes.Route load(String host){List<DirectRoutes.Route> rows=cache.get(host);return rows==null||rows.isEmpty()?null:rows.get(0);}
            public void save(String host,DirectRoutes.Route route){cache.put(host,new ArrayList<>(Collections.singletonList(route)));}
            public List<DirectRoutes.Route> loadAll(String host){return new ArrayList<>(cache.getOrDefault(host,Collections.emptyList()));}
            public void saveAll(String host,List<DirectRoutes.Route> rows){cache.put(host,new ArrayList<>(rows));}
            public void remove(String host){cache.remove(host);} public void clear(){cache.clear();}
            public void reject(DirectRoutes.Route route){List<DirectRoutes.Route> rows=cache.get(route.host);if(rows!=null)rows.removeIf(r->r.key().equals(route.key()));}
        },message->{},45000,ip->space.contains(ip)&&DirectRoutes.automaticAddress(ip));
    }
    private void prepare()throws IOException {
        if(prepared)return;prepared=true;
        try{space=catalog.load();}catch(IOException ignored){space=bootstrap.subset;}
    }
    private void probe(OkHttpClient client,String host)throws IOException {
        String path=host.equals("www.googleapis.com")?"/drive/v3/about?fields=user":host.equals("oauth2.googleapis.com")?"/token":"/";
        client=client.newBuilder().callTimeout(5,TimeUnit.SECONDS).connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).build();
        try(Response response=client.newCall(new Request.Builder().url("https://"+host+path).build()).execute()){
            if(host.equals("www.googleapis.com")) {
                try {if(response.code()!=401||new JSONObject(response.peekBody(8192).string()).getJSONObject("error").getInt("code")!=401)throw new IOException("API probe rejected");}
                catch(JSONException error){throw new IOException("API probe format rejected");}
            } else if(response.code()<200||response.code()>=500||response.code()>=300&&response.code()<400)throw new IOException("Service probe rejected");
        }
    }
    public HttpsURLConnection open(URL url)throws IOException {
        String host=url.getHost();
        if(!url.getProtocol().equals("https")||!(DirectNet.googleHost(host)||host.equals("oauth2.googleapis.com"))||url.getUserInfo()!=null||url.getRef()!=null||(url.getPort()!=-1&&url.getPort()!=443))throw new IOException("Origin rejected");
        Set<String> failed=new HashSet<>();DirectRoutes.Route[] selected={null};
        // First allow the genuine Google hostname under the same GTS-only trust
        // policy. This can use normal DNS without expanding IP discovery.
        boolean[] normal={!normalFailed.contains(host)};
        return GoogleHttp.open(url,()->{
            if(normal[0]){
                Long checked=normalVerified.get(host);
                if(checked==null||System.nanoTime()-checked>TimeUnit.MINUTES.toNanos(5)){
                    try{probe(trusted,host);normalVerified.put(host,System.nanoTime());}
                    catch(IOException error){normal[0]=false;normalFailed.add(host);}
                }
                if(normal[0])return trusted;
            }
            selected[0]=routes.select(host,failed);return clients.get(selected[0]);
        },()->{normal[0]=false;normalFailed.add(host);normalVerified.remove(host);if(selected[0]!=null){failed.add(selected[0].key());routes.reject(selected[0]);}},()->{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();});
    }
    public void check()throws IOException {normalVerified.clear();normalFailed.clear();prepared=false;catalog.retryRefresh();try{probe(trusted,"www.googleapis.com");normalVerified.put("www.googleapis.com",System.nanoTime());}catch(IOException error){normalFailed.add("www.googleapis.com");routes.check("www.googleapis.com");}}
    public void close(){routes.reset();clients.clear();trusted.dispatcher().executorService().shutdownNow();trusted.connectionPool().evictAll();}
}
