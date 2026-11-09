package dev.gdirect.lite.network;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Independently designed, bounded discovery. Candidates are hints, never trust anchors. */
public final class DirectRoutes {
    public static final long MAX_AGE = TimeUnit.HOURS.toMillis(24);
    public enum TlsMode { NORMAL, GOOGLE_FRONT, NO_SNI }
    public static final class Route {
        public final String host, ip;
        public final TlsMode tls;
        public final long checkedAt;
        public Route(String host, String ip, TlsMode tls, long checkedAt) {
            this.host = host; this.ip = ip; this.tls = tls; this.checkedAt = checkedAt;
        }
        public String key() { return ip + "/" + tls; }
    }
    public interface Candidates {
        List<String> get(String host) throws IOException;
        default List<String> preferred(String host)throws IOException{return java.util.Collections.emptyList();}
        default List<String> expand(String host,List<String> observed)throws IOException{return java.util.Collections.emptyList();}
    }
    public interface Probe {
        void check(Route route) throws IOException;
        default void connect(String ip)throws IOException { }
    }
    public interface Store {
        Route load(String host);
        void save(String host, Route route);
        void remove(String host);
        void clear();
        default List<Route> loadAll(String host) { Route route=load(host);return route==null?java.util.Collections.emptyList():java.util.Collections.singletonList(route); }
        default void saveAll(String host,List<Route> routes) { if(!routes.isEmpty())save(host,routes.get(0)); }
        default void reject(Route route) { remove(route.host); }
    }
    public interface Observer { void update(String message); }
    public enum Phase {SELECT_BEGIN,SELECT_OK,SELECT_FAILED,CACHE_HIT,PREFERRED_BEGIN,PREFERRED_FALLBACK,DISCOVERY_BEGIN,PROBE_OK,PROBE_FAILED}
    public interface Trace {
        default long operation(){return 0;}
        default void event(Phase phase,long operation,String host,Route route,long millis,Throwable error){}
    }
    private final Trace trace;
    private final Candidates candidates;
    private final Probe probe;
    private final Store store;
    private final Observer observer;
    private final long budgetMillis;
    private final java.util.function.Predicate<String> permitted;
    private final ReentrantLock gate = new ReentrantLock();
    private final Object cacheLock = new Object();
    private volatile long generation;
    private final java.util.Map<String, Long> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> rejected = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Route> recovery = new java.util.concurrent.ConcurrentHashMap<>();

    public DirectRoutes(Candidates candidates, Probe probe, Store store, Observer observer) {
        this(candidates, probe, store, observer, 45000);
    }
    public DirectRoutes(Candidates candidates, Probe probe, Store store, Observer observer, long budgetMillis) {
        this(candidates,probe,store,observer,budgetMillis,DirectRoutes::automaticAddress);
    }
    public DirectRoutes(Candidates candidates,Probe probe,Store store,Observer observer,long budgetMillis,java.util.function.Predicate<String> permitted) {
        this(candidates,probe,store,observer,budgetMillis,permitted,new Trace(){});
    }
    public DirectRoutes(Candidates candidates,Probe probe,Store store,Observer observer,long budgetMillis,java.util.function.Predicate<String> permitted,Trace trace) {
        this.trace=trace;
        this.candidates = candidates; this.probe = probe; this.store = store;
        this.observer = observer; this.budgetMillis = budgetMillis;this.permitted=permitted;
    }

    public static boolean publicIpv4(String value) {
        return IpLiteral.publicAddress(value) && !IpLiteral.ipv6(value);
    }

    // Identified by the service author's correction as a third-party reverse proxy.
    // A public address and a valid certificate alone do not establish Google ownership.
    public static boolean automaticAddress(String ip) {
        return IpLiteral.publicAddress(ip) && !"183.56.143.147".equals(ip);
    }

    public static List<String> candidates(List<String> freshDns, List<String> history) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String value : IpLiteral.interleave(freshDns)) {
            if (automaticAddress(value)) ordered.add(value);
            if (ordered.size() >= 8) break;
        }
        for (String value : IpLiteral.interleave(history)) {
            if (automaticAddress(value)) ordered.add(value);
            if (ordered.size() >= 16) break;
        }
        return new ArrayList<>(ordered);
    }

    public static List<String> candidates(List<String> system,List<String> encrypted,List<String> history) {
        system=IpLiteral.interleave(system);encrypted=IpLiteral.interleave(encrypted);
        LinkedHashSet<String> fresh=new LinkedHashSet<>();
        // Neither a poisoned system answer set nor one resolver may consume all slots.
        for(int i=0;i<Math.max(system.size(),encrypted.size())&&fresh.size()<8;i++) {
            if(i<encrypted.size()&&automaticAddress(encrypted.get(i)))fresh.add(encrypted.get(i));
            if(i<system.size()&&fresh.size()<8&&automaticAddress(system.get(i)))fresh.add(system.get(i));
        }
        return candidates(new ArrayList<>(fresh),history);
    }

    public Route select(String host, Set<String> failed) throws IOException {
        return select(host,failed,false);
    }
    /** Recheck current service reachability without clearing live routes, pools or network epochs. */
    public Route check(String host)throws IOException {
        return select(host,java.util.Collections.emptySet(),true);
    }
    private Route select(String host,Set<String> failed,boolean recheck)throws IOException {
        long operation=trace.operation(),start=System.nanoTime();
        trace(Phase.SELECT_BEGIN,operation,host,null,0,null);
        try {
            Route route=selectInternal(host,failed,operation,recheck);
            trace(Phase.SELECT_OK,operation,host,route,elapsed(start),null);return route;
        }catch(IOException|RuntimeException error){trace(Phase.SELECT_FAILED,operation,host,null,elapsed(start),error);throw error;}
    }
    private void trace(Phase phase,long operation,String host,Route route,long millis,Throwable error) {
        try{trace.event(phase,operation,host,route,millis,error);}catch(RuntimeException ignored){}
    }
    private static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    private Route selectInternal(String host,Set<String> failed,long operation,boolean recheck)throws IOException {
        if (!DirectNet.googleHost(host) && !host.equals("oauth2.googleapis.com")) throw new IOException("不允许的直连域名");
        try { gate.lockInterruptibly(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw interrupted(); }
        try {
            long epoch = generation;
            long now = System.currentTimeMillis();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
            if(recheck)for(Route cached:store.loadAll(host)) {
                if(!usable(cached,host,failed,now))continue;
                long began=System.nanoTime();
                boolean validated=false;
                try {
                    probe.check(cached);
                    validated=true;
                    synchronized(cacheLock) {
                        if(Thread.currentThread().isInterrupted())throw interrupted();
                        if(generation!=epoch)throw new IOException("网络已切换，请重新检测");
                        Route fresh=new Route(host,cached.ip,cached.tls,System.currentTimeMillis());
                        List<Route> retained=new ArrayList<>();retained.add(fresh);
                        for(Route other:store.loadAll(host))if(retained.size()<8&&usable(other,host,failed,now)&&!other.key().equals(fresh.key()))retained.add(other);
                        rejected.remove(host+"/"+fresh.key());failures.remove(host);store.saveAll(host,retained);
                        trace(Phase.PROBE_OK,operation,host,fresh,elapsed(began),null);observer.update(description(fresh,false));return fresh;
                    }
                }catch(IOException|RuntimeException error) {
                    if(Thread.currentThread().isInterrupted())throw interrupted();
                    if(validated)throw error;
                    synchronized(cacheLock) {
                        if(generation!=epoch)throw new IOException("网络已切换，请重新检测");
                        reject(cached);
                    }
                    trace(Phase.PROBE_FAILED,operation,host,cached,elapsed(began),error);
                    break; // one bounded cached attempt; normal verified fallback handles the rest
                }
            }
            for(Route alternative:store.loadAll(host)) {
                if(!recheck&&alternative!=null&&alternative.host.equals(host)&&permitted.test(alternative.ip)&&alternative.checkedAt<=now
                    &&now-alternative.checkedAt<MAX_AGE&&!failed.contains(alternative.key())&&!recentlyRejected(alternative)) {
                    if(generation!=epoch)throw new IOException("网络已切换，请重新检测直连线路");
                    trace(Phase.CACHE_HIT,operation,host,alternative,0,null);observer.update(description(alternative,true));return alternative;
                }
            }
            Route cached = store.load(host);
            if(cached!=null&&!permitted.test(cached.ip)) {
                synchronized(cacheLock){store.remove(host);}cached=null;
            }
            if (!recheck && cached != null && cached.host.equals(host) && permitted.test(cached.ip)
                    && cached.checkedAt <= now && now - cached.checkedAt < MAX_AGE
                    && !failed.contains(cached.key()) && !recentlyRejected(cached)) {
                if (generation != epoch) throw new IOException("网络已切换，请重新检测直连线路");
                trace(Phase.CACHE_HIT,operation,host,cached,0,null);observer.update(description(cached, true));
                return cached;
            }
            if (generation != epoch) throw new IOException("网络已切换，请重新检测直连线路");
            Route previous=recovery.remove(host);
            if(previous!=null&&permitted.test(previous.ip)) {
                // A route learned through a VPN may stop working with its real SNI.
                // Try the other verified identities before repeating range/DNS discovery.
                Route repaired=verify(host,java.util.Collections.singleton(previous.ip),failed,now,epoch,
                    Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(5500,Math.max(1,budgetMillis/4)))),0,operation);
                if(repaired!=null)return repaired;
            }
            Long recent = failures.get(host);
            if (!recheck && recent != null && System.nanoTime() - recent < TimeUnit.SECONDS.toNanos(10))
                throw new IOException("刚才未找到可用直连线路；请稍后重试或点重新检测直连");
            // An explicitly allowed fixed endpoint needs no Google-ownership catalog or DNS.
            // Bound its head start; failed preference must leave time for normal discovery.
            LinkedHashSet<String> preferred=new LinkedHashSet<>();
            for(String ip:candidates.preferred(host))if(permitted.test(ip)){preferred.add(ip);if(preferred.size()==2)break;}
            if(!preferred.isEmpty()) {
                trace(Phase.PREFERRED_BEGIN,operation,host,null,0,null);
                Route immediate=verify(host,preferred,failed,now,epoch,
                    Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(5500,Math.max(1,budgetMillis/4)))),0,operation);
                if(immediate!=null)return immediate;
                trace(Phase.PREFERRED_FALLBACK,operation,host,null,0,null);
            }
            trace(Phase.DISCOVERY_BEGIN,operation,host,null,0,null);
            observer.update("正在自动收集 " + host + " 的候选线路…");
            LinkedHashSet<String> ips = new LinkedHashSet<>();
            if (cached != null && permitted.test(cached.ip)) ips.add(cached.ip);
            for (String ip : candidates.get(host)) {
                if (permitted.test(ip)) ips.add(ip);
                if (ips.size() >= 48) break;
            }
            Route found=verify(host,ips,failed,now,epoch,Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(10)),operation);
            if(found!=null)return found;
            if(System.nanoTime()<deadline) {
                LinkedHashSet<String> expanded=new LinkedHashSet<>();
                int v4=0,v6=0;
                for(String ip:candidates.expand(host,new ArrayList<>(ips)))if(!ips.contains(ip)&&permitted.test(ip)) {
                    if(IpLiteral.ipv6(ip)){if(v6>=128)continue;if(expanded.add(ip))v6++;}
                    else {if(v4>=512)continue;if(expanded.add(ip))v4++;}
                    if(v4>=512&&v6>=128)break;
                }
                found=verify(host,expanded,failed,now,epoch,deadline,operation);if(found!=null)return found;
            }
            String message="未找到 "+host+" 的可用连接。可在连接设置中重新检测或切换模式；VPN/TUN 可能接管连接。";
            observer.update(message);failures.put(host,System.nanoTime());throw new IOException(message);
        } finally { gate.unlock(); }
    }

    private boolean usable(Route route,String host,Set<String> failed,long now) {
        return route!=null&&route.host.equals(host)&&permitted.test(route.ip)&&route.checkedAt<=now
            &&now-route.checkedAt<MAX_AGE&&!failed.contains(route.key())&&!recentlyRejected(route);
    }

    private Route verify(String host,Set<String> ips,Set<String> failed,long now,long epoch,long deadline,long operation)throws IOException {
        return verify(host,ips,failed,now,epoch,deadline,800,operation);
    }
    private Route verify(String host,Set<String> ips,Set<String> failed,long now,long epoch,long deadline,long collectMillis,long operation)throws IOException {
        List<Route> attempts = new ArrayList<>();
        // Identity alternatives enter the bounded queue; a blocked mode cannot starve every other mode.
        for (String ip : ips) for (TlsMode mode : TlsMode.values()) {
            Route route = new Route(host, ip, mode, now);
            if (!failed.contains(route.key()) && !recentlyRejected(route)) attempts.add(route);
        }
        int checked = 0;
        Probe traced=new Probe(){
            public void connect(String ip)throws IOException{probe.connect(ip);}
            public void check(Route route)throws IOException {
                long start=System.nanoTime();
                try{probe.check(route);trace(Phase.PROBE_OK,operation,host,route,elapsed(start),null);}
                catch(IOException|RuntimeException error){trace(Phase.PROBE_FAILED,operation,host,route,elapsed(start),error);throw error;}
            }
        };
        try(RouteProbeQueue queue=new RouteProbeQueue(attempts,traced)) {
            observer.update("正在验证 " + host + " 的连接与证书，可随时取消…");
            List<Route> verified=new ArrayList<>();
            long collectUntil=deadline;
            while (queue.active()) {
                if (generation != epoch) throw new IOException("网络已切换，请重试以检测新线路");
                long remaining = Math.min(deadline,collectUntil) - System.nanoTime();
                if (remaining <= 0) break;
                RouteProbeQueue.Result ready = queue.poll(remaining);
                if (ready == null) break;
                checked++;
                if(ready.route!=null) {
                    Route found = ready.route;
                    Route fresh = new Route(host, found.ip, found.tls, System.currentTimeMillis());
                    synchronized (cacheLock) {
                        if (Thread.currentThread().isInterrupted()) throw interrupted();
                        if (generation != epoch) throw new IOException("网络已切换，请重试");
                        verified.add(fresh);
                        store.saveAll(host,new ArrayList<>(verified));
                    }
                    failures.remove(host);
                    observer.update(description(verified.get(0), false));
                    if(verified.size()==1)collectUntil=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(collectMillis));
                    if(verified.size()>=8)return verified.get(0);
                } else {
                    // Only counts/canonical host enter diagnostics, never request URLs or tokens.
                    observer.update("正在检测 " + host + "：已完成 " + checked + " 项检查");
                }
            }
            if(!verified.isEmpty()){observer.update(description(verified.get(0),false));return verified.get(0);}
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            observer.update("直连检测已取消");
            throw interrupted();
        }
    }

    public void reset() { synchronized (cacheLock) { generation++; store.clear(); failures.clear(); rejected.clear(); recovery.clear(); } }
    public void reject(Route route) {
        synchronized (cacheLock) {
            rejected.put(route.host + "/" + route.key(),System.nanoTime());
            recovery.put(route.host,route);
            if(recovery.size()>32)for(String host:recovery.keySet())if(!host.equals(route.host)){recovery.remove(host);break;}
            store.reject(route);
        }
    }
    private boolean recentlyRejected(Route route) {
        String key = route.host + "/" + route.key();
        Long time = rejected.get(key);
        if (time == null) return false;
        if (System.nanoTime() - time < TimeUnit.SECONDS.toNanos(30)) return true;
        rejected.remove(key,time); return false;
    }
    private static InterruptedIOException interrupted() { return new InterruptedIOException("直连检测已取消"); }
    public static String description(Route route, boolean cached) {
        return (route.ip.equals("183.56.143.147")?"第三方中转 · ":"Google 前端 · ")
            +(IpLiteral.ipv6(route.ip)?"IPv6 · ":"IPv4 · ")+route.host + " → " + route.ip
            + (route.tls == TlsMode.NO_SNI ? " · 无 SNI" : route.tls==TlsMode.GOOGLE_FRONT?" · Google 前端身份":" · 标准 TLS")
            + (cached ? " · 已缓存" : " · 已通过证书及连通性检查");
    }
}
