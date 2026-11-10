package dev.gdirect.lite.auth;

import dev.gdirect.lite.drive.DriveApi;
import dev.gdirect.lite.drive.DriveSession;
import java.util.Set;

/** Stores only an account selector and local permission choice. Tokens belong to the live session. */
public final class RememberedAuthorization {
    public enum State { IDLE, RESTORING, CONNECTED, NEEDS_ACTION, FAILED }
    public enum Event { AUTH_RESTORE_BEGIN, AUTH_RESTORE_OK, AUTH_RESTORE_REQUIRED, AUTH_RESTORE_FAILED, AUTH_RESTORE_RETRY }
    public static final long RETRY_DELAY_MILLIS=5000;
    public static final class Profile {
        public final String account;
        public final boolean manage;
        public Profile(String account,boolean manage){
            if(account==null||account.isEmpty()||account.length()>320||!account.equals(account.trim())
                ||account.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid account selector");
            this.account=account;this.manage=manage;
        }
    }
    public interface Store { Profile read();void write(Profile profile);void clear(); }
    public interface Callback {
        void success(String account,String token,Set<String> scopes);
        void needsAction();void failed(int code);
    }
    public interface Provider { void restore(Profile profile,Callback callback); }
    public interface Observer { void event(Event event,int code); }
    private final DriveSession session;
    private final Store store;
    private final Provider provider;
    private final Observer observer;
    private final java.util.function.LongSupplier clock;
    private State state=State.IDLE;
    private long generation;
    private long attemptedPath=Long.MIN_VALUE,failedAt;
    private int failureCode,retries;

    public RememberedAuthorization(DriveSession session,Store store,Provider provider,Observer observer){
        this(session,store,provider,observer,()->java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }
    public RememberedAuthorization(DriveSession session,Store store,Provider provider,Observer observer,java.util.function.LongSupplier clock){
        this.session=session;this.store=store;this.provider=provider;this.observer=observer;
        this.clock=clock;
    }
    public synchronized State state(){return state;}
    public synchronized boolean remembered(){return store.read()!=null;}
    public synchronized Profile profile(){return store.read();}
    private void emit(Event event,int code){try{observer.event(event,code);}catch(RuntimeException ignored){}}

    /** One application-owned request, shared by recreated screens; never launches consent UI. */
    public boolean restore(){
        return restore(false,0,true);
    }
    /** An online browser may recover SDK network/timeouts once, or restart on a changed path. */
    public boolean restore(long networkGeneration,boolean online){
        return restore(true,networkGeneration,online);
    }
    private boolean restore(boolean automatic,long networkGeneration,boolean online){
        final Profile profile;final long ticket;
        synchronized(this){
            if(!online||session.connected()||state==State.RESTORING||state==State.NEEDS_ACTION)return false;
            profile=store.read();if(profile==null)return false;
            if(state==State.FAILED){
                // SDK NETWORK_ERROR/TIMEOUT only. Configuration, consent, cancellation and API 401 wait for interaction.
                if(!automatic||(failureCode!=7&&failureCode!=15))return false;
                if(attemptedPath==networkGeneration){
                    if(retries>=1||clock.getAsLong()-failedAt<RETRY_DELAY_MILLIS)return false;
                    retries++;
                }else retries=0;
                emit(Event.AUTH_RESTORE_RETRY,failureCode);
            }else retries=0;
            attemptedPath=networkGeneration;
            ticket=++generation;state=State.RESTORING;emit(Event.AUTH_RESTORE_BEGIN,0);
        }
        Callback callback=new Callback(){
            public void success(String account,String token,Set<String> scopes){
                synchronized(RememberedAuthorization.this){
                    if(ticket!=generation||state!=State.RESTORING)return;
                    String required=profile.manage?DriveApi.MANAGE_SCOPE:DriveApi.READONLY_SCOPE;
                    boolean readable=scopes!=null&&(scopes.contains(DriveApi.READONLY_SCOPE)||scopes.contains(DriveApi.MANAGE_SCOPE));
                    if(account==null||!profile.account.equalsIgnoreCase(account)||!readable
                        ||(profile.manage&&!scopes.contains(required))){finish(State.NEEDS_ACTION,Event.AUTH_RESTORE_REQUIRED,0);return;}
                    try{session.connect(token,scopes,profile.manage);finish(State.CONNECTED,Event.AUTH_RESTORE_OK,0);}
                    catch(IllegalArgumentException invalid){finish(State.NEEDS_ACTION,Event.AUTH_RESTORE_REQUIRED,0);}
                }
            }
            public void needsAction(){synchronized(RememberedAuthorization.this){if(ticket==generation&&state==State.RESTORING)finish(State.NEEDS_ACTION,Event.AUTH_RESTORE_REQUIRED,0);}}
            public void failed(int code){synchronized(RememberedAuthorization.this){if(ticket==generation&&state==State.RESTORING){
                failureCode=code;failedAt=clock.getAsLong();finish(State.FAILED,Event.AUTH_RESTORE_FAILED,code);
            }}}
        };
        try{provider.restore(profile,callback);}catch(RuntimeException error){callback.failed(-1);}
        return true;
    }
    private void finish(State outcome,Event event,int code){generation++;state=outcome;emit(event,code);}
    /** Explicit interaction supersedes any older asynchronous restore. */
    public synchronized long beginInteractive(){generation++;state=State.NEEDS_ACTION;return generation;}
    public synchronized void interactiveFailed(long ticket){
        if(ticket!=generation||state!=State.NEEDS_ACTION)return;
        generation++;if(session.connected())state=State.CONNECTED;
    }
    public synchronized boolean acceptInteractive(long ticket,String token,Set<String> scopes,boolean allowWrite,String account){
        if(ticket!=generation||state!=State.NEEDS_ACTION)return false;
        Profile profile=account==null?null:new Profile(account,allowWrite&&scopes!=null&&scopes.contains(DriveApi.MANAGE_SCOPE));
        session.connect(token,scopes,allowWrite);
        // A missing selector must never leave an older account scheduled for future restoration.
        if(profile==null)store.clear();else store.write(profile);
        generation++;state=State.CONNECTED;return true;
    }
    public synchronized void forget(){generation++;store.clear();session.disconnect();state=State.IDLE;failureCode=0;retries=0;attemptedPath=Long.MIN_VALUE;}
    /** An API 401 requires a user decision rather than an endless silent reconnect loop. */
    public synchronized String rejected(DriveSession.Lease lease){
        try{
            String token=lease.token();session.invalidate(lease);generation++;state=State.NEEDS_ACTION;return token;
        }catch(DriveSession.AuthorizationRequired stale){return null;}
    }
}
