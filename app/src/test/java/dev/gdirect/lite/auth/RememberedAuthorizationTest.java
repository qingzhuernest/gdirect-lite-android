package dev.gdirect.lite.auth;

import static org.junit.Assert.*;
import dev.gdirect.lite.drive.DriveApi;
import dev.gdirect.lite.drive.DriveSession;
import java.util.*;
import org.junit.Test;

public final class RememberedAuthorizationTest {
    private static final String FIRST="first@example.test",SECOND="second@example.test";
    private static final Set<String> READ=Collections.singleton(DriveApi.READONLY_SCOPE);
    private static final Set<String> MANAGE=Collections.singleton(DriveApi.MANAGE_SCOPE);
    private static final class Storage implements RememberedAuthorization.Store {
        RememberedAuthorization.Profile profile;
        public RememberedAuthorization.Profile read(){return profile;}
        public void write(RememberedAuthorization.Profile value){profile=value;}
        public void clear(){profile=null;}
    }
    private static final class Fixture {
        final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong();
        final DriveSession session=new DriveSession(()->java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(clock.get()));final Storage store;
        final List<RememberedAuthorization.Callback> callbacks=new ArrayList<>();
        final List<RememberedAuthorization.Profile> requests=new ArrayList<>();
        final List<String> previousTokens=new ArrayList<>();
        final RememberedAuthorization memory;
        Fixture(){this(new Storage());}
        Fixture(Storage store){this.store=store;memory=new RememberedAuthorization(session,store,new RememberedAuthorization.Provider(){
            public void restore(RememberedAuthorization.Profile profile,RememberedAuthorization.Callback callback){restore(profile,null,callback);}
            public void restore(RememberedAuthorization.Profile profile,String previous,RememberedAuthorization.Callback callback){previousTokens.add(previous);requests.add(profile);callbacks.add(callback);}
        },(event,code)->{},clock::get);}
        void remember(boolean manage){store.profile=new RememberedAuthorization.Profile(FIRST,manage);}
        RememberedAuthorization.Callback callback(){return callbacks.get(callbacks.size()-1);}
    }
    @Test public void coldProcessRestoresTheSameAccountWithoutPersistingItsAccessToken()throws Exception {
        Fixture first=new Fixture();long ticket=first.memory.beginInteractive();
        assertTrue(first.memory.acceptInteractive(ticket,"CANARY_LIVE_TOKEN",READ,false,FIRST));
        assertEquals(FIRST,first.store.profile.account);assertFalse(first.store.profile.manage);
        Fixture reopened=new Fixture(first.store);assertFalse(reopened.session.connected());assertTrue(reopened.memory.restore());
        assertEquals(FIRST,reopened.requests.get(0).account);
        reopened.callback().success(FIRST,"fresh-token",MANAGE);
        assertTrue(reopened.session.connected());assertFalse(reopened.session.writable());assertEquals("fresh-token",reopened.session.lease().token());
    }
    @Test public void noRememberedAccountDoesNotStartAnAuthorizationRequest(){
        Fixture f=new Fixture();assertFalse(f.memory.restore());assertTrue(f.callbacks.isEmpty());assertFalse(f.memory.remembered());
    }
    @Test public void recreatedScreensShareOnePendingRestore(){
        Fixture f=new Fixture();f.remember(false);assertTrue(f.memory.restore());
        assertFalse(f.memory.restore());assertFalse(f.memory.restore());assertEquals(1,f.callbacks.size());assertEquals(RememberedAuthorization.State.RESTORING,f.memory.state());
    }
    @Test public void aConsentRequirementWaitsForUserInteractionWithoutRepeatedRequests(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore();f.callback().needsAction();
        assertEquals(RememberedAuthorization.State.NEEDS_ACTION,f.memory.state());assertFalse(f.memory.restore());assertFalse(f.session.connected());assertTrue(f.memory.remembered());
    }
    @Test public void temporaryFailureKeepsAccountAndManualRetryCanReconnect(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore();f.callback().failed(7);
        assertEquals(RememberedAuthorization.State.FAILED,f.memory.state());assertTrue(f.memory.remembered());assertFalse(f.memory.restore());
        long ticket=f.memory.beginInteractive();assertTrue(f.memory.acceptInteractive(ticket,"retry-token",READ,false,FIRST));assertTrue(f.session.connected());
    }
    @Test public void disconnectDuringRestoreCannotBeUndoneByALateCallback(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore();RememberedAuthorization.Callback late=f.callback();f.memory.forget();
        late.success(FIRST,"late-token",READ);assertFalse(f.session.connected());assertFalse(f.memory.remembered());assertFalse(f.memory.restore());
    }
    @Test public void manualAccountSelectionSupersedesAnOlderRestore()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore();RememberedAuthorization.Callback late=f.callback();
        long ticket=f.memory.beginInteractive();assertTrue(f.memory.acceptInteractive(ticket,"second-token",READ,false,SECOND));
        late.success(FIRST,"stale-first-token",READ);assertEquals(SECOND,f.store.profile.account);assertEquals("second-token",f.session.lease().token());
    }
    @Test public void providerCannotSilentlyRestoreADifferentAccount(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore();f.callback().success(SECOND,"wrong-account-token",READ);
        assertFalse(f.session.connected());assertEquals(RememberedAuthorization.State.NEEDS_ACTION,f.memory.state());assertEquals(FIRST,f.store.profile.account);
    }
    @Test public void rememberedManagementRequiresGrantedManagementScope(){
        Fixture f=new Fixture();f.remember(true);f.memory.restore();assertTrue(f.requests.get(0).manage);f.callback().success(FIRST,"read-only-token",READ);
        assertFalse(f.session.connected());assertEquals(RememberedAuthorization.State.NEEDS_ACTION,f.memory.state());
        Fixture approved=new Fixture();approved.remember(true);approved.memory.restore();approved.callback().success(FIRST,"manage-token",MANAGE);assertTrue(approved.session.writable());
    }
    @Test public void api401StopsAutomaticReconnectAndAnOldLeaseCannotInvalidateANewSession()throws Exception {
        Fixture f=new Fixture();long ticket=f.memory.beginInteractive();f.memory.acceptInteractive(ticket,"old-token",READ,false,FIRST);
        DriveSession.Lease old=f.session.lease();assertEquals("old-token",f.memory.rejected(old));assertFalse(f.memory.restore());assertTrue(f.memory.remembered());
        ticket=f.memory.beginInteractive();f.memory.acceptInteractive(ticket,"new-token",READ,false,SECOND);
        assertNull(f.memory.rejected(old));assertTrue(f.session.connected());assertEquals("new-token",f.session.lease().token());
    }
    @Test public void missingSelectorDoesNotRetainAnOlderRememberedAccount(){
        Fixture f=new Fixture();f.remember(false);long ticket=f.memory.beginInteractive();
        assertTrue(f.memory.acceptInteractive(ticket,"valid-token",READ,false,null));assertTrue(f.session.connected());assertFalse(f.memory.remembered());
    }
    @Test public void invalidTokensScopesAndAccountSelectorsNeverCreateARestoredSession(){
        for(String token:new String[]{null,"","Bearer secret\n"}){
            Fixture f=new Fixture();f.remember(false);f.memory.restore();f.callback().success(FIRST,token,READ);assertFalse(f.session.connected());assertEquals(RememberedAuthorization.State.NEEDS_ACTION,f.memory.state());
        }
        Fixture f=new Fixture();f.remember(false);f.memory.restore();f.callback().success(FIRST,"token",Collections.emptySet());assertFalse(f.session.connected());
        for(String account:new String[]{""," account ","bad\naccount","x".repeat(321)})assertThrows(IllegalArgumentException.class,()->new RememberedAuthorization.Profile(account,false));
    }
    @Test public void anExpiredLiveSessionCanRestoreAgainWithoutDiscardingItsRememberedAccount(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore();f.callback().success(FIRST,"initial-token",READ);f.session.disconnect();
        assertTrue(f.memory.restore());assertEquals(2,f.callbacks.size());f.callback().success(FIRST,"fresh-token",READ);assertTrue(f.session.connected());
    }
    @Test public void logoutRejectsOldInteractiveResultsAndResultsCannotBeReplayed(){
        Fixture f=new Fixture();assertFalse(f.memory.acceptInteractive(0,"token",READ,false,FIRST));
        long old=f.memory.beginInteractive();f.memory.forget();assertFalse(f.memory.acceptInteractive(old,"old-token",READ,false,FIRST));
        long current=f.memory.beginInteractive();assertTrue(f.memory.acceptInteractive(current,"token",READ,false,SECOND));
        assertFalse(f.memory.acceptInteractive(current,"replayed",READ,false,FIRST));assertEquals(SECOND,f.store.profile.account);
    }
    @Test public void cancellingPermissionExpansionPreservesTheExistingReadOnlySessionAndFutureRestore(){
        Fixture f=new Fixture();long ticket=f.memory.beginInteractive();f.memory.acceptInteractive(ticket,"read-token",READ,false,FIRST);
        long management=f.memory.beginInteractive();f.memory.interactiveFailed(management);
        assertTrue(f.session.connected());assertFalse(f.session.writable());assertEquals(RememberedAuthorization.State.CONNECTED,f.memory.state());
        assertFalse(f.memory.acceptInteractive(management,"late-manage-token",MANAGE,true,FIRST));
        f.session.disconnect();assertTrue(f.memory.restore());assertFalse(f.requests.get(0).manage);
    }
    @Test public void offlineStartupWaitsForNetworkBeforeCallingTheSdk(){
        Fixture f=new Fixture();f.remember(false);
        assertFalse(f.memory.restore(1,false));assertTrue(f.requests.isEmpty());
        assertTrue(f.memory.restore(2,true));assertEquals(FIRST,f.requests.get(0).account);
        assertFalse(f.memory.restore(2,true));assertEquals(1,f.requests.size());
    }
    @Test public void temporarySdkNetworkFailureGetsOneDelayedRetryOnTheSamePath(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().failed(7);
        assertFalse(f.memory.restore(1,true));f.clock.set(4999);assertFalse(f.memory.restore(1,true));
        f.clock.set(5000);assertTrue(f.memory.restore(1,true));f.callback().failed(7);
        for(int i=1;i<100;i++){f.clock.set(5000+i*10000);assertFalse(f.memory.restore(1,true));}
        assertEquals(2,f.requests.size());assertTrue(f.memory.remembered());
    }
    @Test public void sdkTimeoutCanRecoverSilentlyWithoutChangingAccountOrReadOnlyChoice(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().failed(15);
        f.clock.set(5000);assertTrue(f.memory.restore(1,true));f.callback().success(FIRST,"fresh-token",MANAGE);
        assertTrue(f.session.connected());assertFalse(f.session.writable());
        assertEquals(FIRST,f.requests.get(1).account);assertFalse(f.requests.get(1).manage);
    }
    @Test public void aChangedNetworkCanRecoverAnExhaustedRestoreWithoutWaitingAgain(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().failed(7);
        f.clock.set(5000);f.memory.restore(1,true);f.callback().failed(7);
        assertFalse(f.memory.restore(2,false));assertTrue(f.memory.restore(2,true));
        f.callback().failed(15);assertFalse(f.memory.restore(2,true));
        f.clock.set(10000);assertTrue(f.memory.restore(2,true));assertEquals(4,f.requests.size());
    }
    @Test public void nonNetworkSdkFailuresNeverRetryAcrossTicksOrPathChanges(){
        for(int code:new int[]{-1,4,8,10,13,16,12500,12501}){
            Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().failed(code);
            f.clock.set(100000);assertFalse(f.memory.restore(1,true));assertFalse(f.memory.restore(2,true));
            assertEquals(1,f.requests.size());assertTrue(f.memory.remembered());
        }
    }
    @Test public void aRequiredConsentOrApi401DoesNotBecomeAnAutomaticSdkLoop()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().needsAction();
        f.clock.set(100000);assertFalse(f.memory.restore(2,true));
        long ticket=f.memory.beginInteractive();f.memory.acceptInteractive(ticket,"token",READ,false,FIRST);
        f.memory.rejected(f.session.lease());assertFalse(f.memory.restore(3,true));assertEquals(1,f.requests.size());
    }
    @Test public void logoutAndInteractiveAccountSwitchSupersedePendingAutomaticRetry(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().failed(7);
        f.clock.set(5000);f.memory.restore(1,true);RememberedAuthorization.Callback late=f.callback();
        f.memory.forget();late.success(FIRST,"late-token",READ);assertFalse(f.memory.restore(2,true));
        f.remember(false);f.memory.restore(2,true);f.callback().failed(15);
        long ticket=f.memory.beginInteractive();f.memory.acceptInteractive(ticket,"selected-token",READ,false,SECOND);
        f.clock.set(100000);assertFalse(f.memory.restore(3,true));assertEquals(SECOND,f.store.profile.account);
    }
    @Test public void aPathChangeDuringRestoreDoesNotStartASecondConcurrentSdkCall(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);
        assertFalse(f.memory.restore(2,true));assertEquals(1,f.requests.size());f.callback().failed(7);
        assertTrue(f.memory.restore(2,true));assertEquals(2,f.requests.size());
    }
    @Test public void successfulRecoveryCanRestoreAnExpiredSessionAgain(){
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().failed(7);
        f.clock.set(5000);f.memory.restore(1,true);f.callback().success(FIRST,"token",READ);
        f.session.disconnect();assertTrue(f.memory.restore(1,true));f.callback().failed(7);
        f.clock.set(10000);assertTrue(f.memory.restore(1,true));
    }
    @Test public void verifiedRenewalKeepsTheSameAccountLeaseAndResourceKeys()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().success(FIRST,"initial",READ);
        DriveSession.Lease lease=f.session.lease();lease.rememberKey("file","key");
        f.clock.set(45*60000);assertTrue(f.memory.restore(1,true));
        assertEquals("initial",f.previousTokens.get(1));
        f.callback().success(FIRST,"renewed",MANAGE);
        assertEquals(lease.id(),f.session.lease().id());assertEquals("renewed",lease.token());assertEquals("file/key",lease.resourceHeader("file"));
        assertFalse(f.session.writable());
    }
    @Test public void consentRequirementDuringRenewalStopsOldAccountWork()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().success(FIRST,"initial",READ);
        DriveSession.Lease lease=f.session.lease();f.clock.set(45*60000);f.memory.restore(1,true);f.callback().needsAction();
        assertThrows(DriveSession.AuthorizationRequired.class,lease::requireCurrent);assertFalse(f.memory.restore(2,true));
    }
    @Test public void newRequestWaitsForRenewalWhileAnExistingBodyCanContinue()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().success(FIRST,"initial",READ);
        DriveSession.Lease lease=f.session.lease();f.clock.set(51*60000);f.memory.restore(1,true);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var request=java.util.concurrent.CompletableFuture.runAsync(()->{entered.countDown();try{f.memory.requireFresh(lease,1,true);}catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}});
        assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));lease.requireCurrent();assertFalse(request.isDone());
        f.callback().success(FIRST,"fresh",READ);request.get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals("fresh",lease.token());
    }
    @Test public void temporaryRenewalFailureDoesNotDiscardTheAuthorizedBody()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().success(FIRST,"initial",READ);
        DriveSession.Lease lease=f.session.lease();f.clock.set(51*60000);f.memory.restore(1,true);f.callback().failed(7);
        lease.requireCurrent();assertThrows(DriveSession.RefreshRequired.class,()->f.memory.requireFresh(lease,1,true));
        f.clock.addAndGet(5000);assertTrue(f.memory.restore(1,true));f.callback().success(FIRST,"renewed",READ);assertEquals("renewed",lease.token());
    }
    @Test public void anOld401CannotDiscardTheSuccessfullyRenewedToken()throws Exception {
        Fixture f=new Fixture();f.remember(false);f.memory.restore(1,true);f.callback().success(FIRST,"old",READ);
        DriveSession.Lease lease=f.session.lease();f.clock.set(45*60000);f.memory.restore(1,true);f.callback().success(FIRST,"new",READ);
        assertNull(f.memory.rejected(lease,"old"));assertEquals("new",lease.token());assertEquals(RememberedAuthorization.State.CONNECTED,f.memory.state());
    }
}
