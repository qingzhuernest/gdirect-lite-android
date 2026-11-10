package dev.gdirect.lite.auth;

import dev.gdirect.lite.GoogleNetwork;
import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.drive.DriveTransport;

import android.content.Context;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.common.api.Scope;
import dev.gdirect.lite.drive.DriveApi;
import dev.gdirect.lite.diagnostics.DiagnosticLog;

public final class GoogleAuthorization {
    public static final java.util.concurrent.atomic.AtomicBoolean revoking = new java.util.concurrent.atomic.AtomicBoolean();
    public static volatile boolean lastRevokeSucceeded;
    private static RememberedAuthorization remembered;
    public static synchronized RememberedAuthorization memory(Context context){
        if(remembered!=null)return remembered;
        Context app=context.getApplicationContext();
        android.content.SharedPreferences prefs=app.getSharedPreferences("remembered-google-connection",Context.MODE_PRIVATE);
        remembered=new RememberedAuthorization(DriveSession.INSTANCE,new RememberedAuthorization.Store(){
            public RememberedAuthorization.Profile read(){
                try{String account=prefs.getString("account",null);return account==null?null:new RememberedAuthorization.Profile(account,prefs.getBoolean("manage",false));}
                catch(IllegalArgumentException|ClassCastException invalid){return null;}
            }
            public void write(RememberedAuthorization.Profile profile){prefs.edit().clear().putString("account",profile.account).putBoolean("manage",profile.manage).apply();}
            public void clear(){prefs.edit().clear().apply();}
        },new RememberedAuthorization.Provider(){
          public void restore(RememberedAuthorization.Profile profile,RememberedAuthorization.Callback callback){restore(profile,null,callback);}
          public void restore(RememberedAuthorization.Profile profile,String previousToken,RememberedAuthorization.Callback callback){
            AuthorizationRequest request=AuthorizationRequest.builder().setAccount(new android.accounts.Account(profile.account,"com.google"))
                .setRequestedScopes(java.util.Collections.singletonList(new Scope(profile.manage?DriveApi.MANAGE_SCOPE:DriveApi.READONLY_SCOPE))).build();
            android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable timeout=()->callback.failed(15);handler.postDelayed(timeout,30000);
            Runnable authorize=()->Identity.getAuthorizationClient(app).authorize(request).addOnCompleteListener(task->handler.removeCallbacks(timeout)).addOnSuccessListener(result->{
                try{
                    if(result.hasResolution()){callback.needsAction();return;}
                    callback.success(account(result),result.getAccessToken(),new java.util.HashSet<>(result.getGrantedScopes()));
                }catch(RuntimeException invalid){callback.failed(-1);}
            }).addOnFailureListener(error->callback.failed(error instanceof com.google.android.gms.common.api.ApiException?
                ((com.google.android.gms.common.api.ApiException)error).getStatusCode():-1));
            if(previousToken==null)authorize.run();
            else Identity.getAuthorizationClient(app).clearToken(ClearTokenRequest.builder().setToken(previousToken).build())
                .addOnSuccessListener(ignored->authorize.run()).addOnFailureListener(error->{handler.removeCallbacks(timeout);callback.failed(error instanceof com.google.android.gms.common.api.ApiException?
                    ((com.google.android.gms.common.api.ApiException)error).getStatusCode():-1);});
          }
        },(event,code)->DiagnosticLog.get().record(DiagnosticLog.Event.valueOf(event.name()),0,null,null,null,0,code,null));
        return remembered;
    }
    public static String account(AuthorizationResult result){
        com.google.android.gms.auth.api.signin.GoogleSignInAccount identity=result.toGoogleSignInAccount();
        android.accounts.Account account=identity==null?null:identity.getAccount();
        return account!=null&&"com.google".equals(account.type)?account.name:null;
    }
    public static DriveTransport transport(Context context, DriveSession.Lease lease) {
        return transport(context,lease,c->{});
    }
    public static DriveTransport transport(Context context,DriveSession.Lease lease,java.util.function.Consumer<javax.net.ssl.HttpsURLConnection> observer) {
        Context app = context.getApplicationContext();
        return new DriveTransport(lease, url -> {
            GoogleNetwork net=GoogleNetwork.get();
            if("www.googleapis.com".equals(url.getHost())){
                RememberedAuthorization authorization=memory(app);
                boolean online=net.health().online;long path=net.healthGeneration();
                authorization.restore(path,online);authorization.requireFresh(lease,path,online);
            }else lease.requireCurrent();
            javax.net.ssl.HttpsURLConnection c=net.open(url,null,lease::requireCurrent);observer.accept(c);return c;
        }, rejectedToken -> {
            try {
                String rejected = memory(app).rejected(lease,rejectedToken);
                if(rejected!=null)Identity.getAuthorizationClient(app).clearToken(ClearTokenRequest.builder().setToken(rejected).build());
            } catch (RuntimeException ignored) { }
        });
    }

    private GoogleAuthorization() { }
}
