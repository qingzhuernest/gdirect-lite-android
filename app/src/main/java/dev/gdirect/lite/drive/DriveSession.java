package dev.gdirect.lite.drive;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Access tokens exist only in process memory; a generation invalidates in-flight work. */
public final class DriveSession {
    public static final DriveSession INSTANCE = new DriveSession();
    private String token;
    // Pending file-picker/preview intents from a dead process must not match a new account session.
    private long generation=new java.security.SecureRandom().nextLong()&0x3fffffffffffffffL;
    private long expiresAt;
    private long refreshAt;
    private final java.util.function.LongSupplier clock;
    private boolean writable;
    private java.util.Set<String> grants=java.util.Collections.emptySet();
    private final java.util.Map<String,String> resourceKeys=new java.util.LinkedHashMap<>(16,0.75f,true);

    public DriveSession(){this(System::nanoTime);}
    public DriveSession(java.util.function.LongSupplier clock){this.clock=clock;}

    public synchronized void connect(String accessToken) {
        connect(accessToken,false);
    }
    public synchronized void connect(String accessToken,boolean writable) {
        connect(accessToken,java.util.Collections.singleton(writable?DriveApi.MANAGE_SCOPE:DriveApi.READONLY_SCOPE));
    }
    public synchronized void connect(String accessToken,java.util.Set<String> scopes) {
        connect(accessToken,scopes,true);
    }
    public synchronized void connect(String accessToken,java.util.Set<String> scopes,boolean allowWrite) {
        install(accessToken,scopes,allowWrite);
        resourceKeys.clear();generation++;
    }

    /** Only the platform's verified same-account restore may renew an existing lease. */
    public synchronized boolean renew(Lease expected,String accessToken,java.util.Set<String> scopes,boolean allowWrite){
        if(expected==null||expected.owner!=this||expected.generation!=generation||token==null)return false;
        boolean previouslyWritable=writable;
        install(accessToken,scopes,allowWrite);
        // Permission changes are not merely credential renewal: old writes must stop at the guard.
        if(previouslyWritable!=writable)generation++;
        return true;
    }
    private void install(String accessToken,java.util.Set<String> scopes,boolean allowWrite){
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalArgumentException("Google 未返回有效访问令牌");
        if(scopes==null)throw new IllegalArgumentException("Google 未返回有效权限范围");
        java.util.Set<String> accepted=new java.util.HashSet<>();
        if(scopes.contains(DriveApi.READONLY_SCOPE))accepted.add(DriveApi.READONLY_SCOPE);
        if(scopes.contains(DriveApi.MANAGE_SCOPE))accepted.add(DriveApi.MANAGE_SCOPE);
        if(accepted.isEmpty())throw new IllegalArgumentException("未获得 Google Drive 权限");
        token=accessToken;
        grants=java.util.Collections.unmodifiableSet(accepted);this.writable=allowWrite&&accepted.contains(DriveApi.MANAGE_SCOPE);
        // Renew ahead of the conservative credential deadline. Account leases have a separate lifetime.
        refreshAt=clock.getAsLong()+TimeUnit.MINUTES.toNanos(45);
        expiresAt=clock.getAsLong()+TimeUnit.MINUTES.toNanos(50);
    }

    public synchronized Lease lease() throws AuthorizationRequired {
        if (token == null) throw new AuthorizationRequired();
        return new Lease(this, generation);
    }

    public synchronized boolean connected() {
        return token != null && clock.getAsLong() - expiresAt < 0;
    }
    public synchronized boolean refreshDue(){return token!=null&&clock.getAsLong()-refreshAt>=0;}

    public synchronized void disconnect() { token = null; writable=false;grants=java.util.Collections.emptySet();resourceKeys.clear();generation++; }
    public synchronized boolean writable() { return connected() && writable; }
    public synchronized java.util.Set<String> scopes(){return connected()?grants:java.util.Collections.emptySet();}

    public synchronized void invalidate(Lease lease) {
        if (lease.owner == this && lease.generation == generation) disconnect();
    }
    public synchronized boolean invalidate(Lease lease,String rejectedToken){
        if(lease.owner!=this||lease.generation!=generation||token==null||!token.equals(rejectedToken))return false;
        disconnect();return true;
    }

    public static class AuthorizationRequired extends IOException {
        public AuthorizationRequired() { this("Google 授权已失效，请重新连接 Google Drive"); }
        protected AuthorizationRequired(String message){super(message);}
    }
    public static final class RefreshRequired extends AuthorizationRequired {
        public RefreshRequired(){super("账户令牌暂未更新，请稍后重试或重新连接账户");}
    }

    public static final class Lease {
        private final DriveSession owner;
        private final long generation;
        private Lease(DriveSession owner, long generation) { this.owner = owner; this.generation = generation; }
        public long id() { return generation; }
        public String token() throws AuthorizationRequired {
            synchronized (owner) {
                requireCurrent();
                if(!owner.connected())throw new RefreshRequired();
                return owner.token;
            }
        }
        /** Cache removal during verified renewal is not revocation and must not abort a response body. */
        public String renewalToken() throws AuthorizationRequired {
            synchronized(owner){requireCurrent();return owner.token;}
        }
        public void requireCurrent() throws AuthorizationRequired {
            synchronized(owner){if(owner.generation!=generation||owner.token==null)throw new AuthorizationRequired();}
        }
        public void requireWrite() throws IOException {
            synchronized(owner) { requireCurrent(); if(!owner.writable) throw new IOException("请先启用文件管理授权"); }
        }
        public void rememberKey(String id,String key)throws IOException {
            DriveApi.requireId(id);
            if(key==null||key.isEmpty())return;
            if(!key.matches("[A-Za-z0-9_-]{1,200}"))throw new IOException("资源密钥格式无效");
            synchronized(owner){requireCurrent();if(owner.resourceKeys.size()>=64&&!owner.resourceKeys.containsKey(id))owner.resourceKeys.remove(owner.resourceKeys.keySet().iterator().next());owner.resourceKeys.put(id,key);}
        }
        public String resourceHeader(String id)throws IOException {
            if(id==null)return null;DriveApi.requireId(id);
            synchronized(owner){requireCurrent();String key=owner.resourceKeys.get(id);return key==null?null:id+"/"+key;}
        }
    }
}
