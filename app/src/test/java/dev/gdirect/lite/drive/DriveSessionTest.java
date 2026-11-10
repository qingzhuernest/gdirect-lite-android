package dev.gdirect.lite.drive;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DriveSessionTest {
    @Test public void credentialExpiryDoesNotInvalidateAnAuthorizedResponseBody()throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();DriveSession s=new DriveSession(clock::get);s.connect("old-token");
        DriveSession.Lease transfer=s.lease();clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(51));
        assertFalse(s.connected());transfer.requireCurrent();assertEquals(transfer.id(),s.lease().id());
        assertThrows(DriveSession.RefreshRequired.class,transfer::token);
        assertTrue(s.renew(transfer,"fresh-token",java.util.Set.of(DriveApi.READONLY_SCOPE),false));
        assertEquals("fresh-token",transfer.token());transfer.requireCurrent();
    }
    @Test public void sameAccountRenewalPreservesKeysAndAppliesCurrentPermissionChoice()throws Exception {
        DriveSession s=new DriveSession();s.connect("token",true);DriveSession.Lease lease=s.lease();lease.rememberKey("file","key");
        assertTrue(s.renew(lease,"next",java.util.Set.of(DriveApi.MANAGE_SCOPE),false));
        assertNotEquals(lease.id(),s.lease().id());assertEquals("file/key",s.lease().resourceHeader("file"));
        assertThrows(DriveSession.AuthorizationRequired.class,lease::requireCurrent);
        assertThrows(java.io.IOException.class,lease::requireWrite);
    }
    @Test public void renewalCannotResurrectALoggedOutOrReplacedAccount()throws Exception {
        DriveSession s=new DriveSession();s.connect("first");DriveSession.Lease first=s.lease();s.disconnect();
        assertFalse(s.renew(first,"late",java.util.Set.of(DriveApi.READONLY_SCOPE),false));
        s.connect("second");assertFalse(s.renew(first,"late",java.util.Set.of(DriveApi.READONLY_SCOPE),false));
        assertEquals("second",s.lease().token());assertThrows(DriveSession.AuthorizationRequired.class,first::requireCurrent);
    }
    @Test public void malformedRenewalPreservesTheCurrentAccount()throws Exception {
        DriveSession s=new DriveSession();s.connect("first");DriveSession.Lease lease=s.lease();
        assertThrows(IllegalArgumentException.class,()->s.renew(lease,"bad\nheader",java.util.Set.of(DriveApi.READONLY_SCOPE),false));
        assertEquals("first",lease.token());
    }
    @Test public void proactiveRefreshHasFiveMinutesOfHeadroom()throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();DriveSession s=new DriveSession(clock::get);s.connect("token");
        clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(44));assertFalse(s.refreshDue());
        clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(45));assertTrue(s.refreshDue());assertTrue(s.connected());
    }
    @Test public void cachedBroadProviderGrantDoesNotBypassExplicitReadOnlyMode()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",java.util.Collections.singleton(DriveApi.MANAGE_SCOPE),false);
        assertTrue(session.connected());assertFalse(session.writable());assertTrue(session.scopes().contains(DriveApi.MANAGE_SCOPE));
        assertThrows(java.io.IOException.class,()->session.lease().requireWrite());
    }
    @Test public void aFreshProcessSessionDoesNotReusePersistedPickerGeneration()throws Exception {
        DriveSession oldProcess=new DriveSession(),newProcess=new DriveSession();
        oldProcess.connect("old-account");newProcess.connect("new-account");
        assertNotEquals(oldProcess.lease().id(),newProcess.lease().id());
    }
    @Test public void actualGrantedScopesAreKeptForRevocationAndInvalidScopesPreserveOldSession()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",new java.util.HashSet<>(java.util.Arrays.asList(DriveApi.READONLY_SCOPE,DriveApi.MANAGE_SCOPE,"unrelated")));
        assertEquals(2,session.scopes().size());assertTrue(session.writable());DriveSession.Lease lease=session.lease();
        assertThrows(IllegalArgumentException.class,()->session.connect("other",java.util.Collections.singleton("unrelated")));
        lease.requireWrite();assertEquals("fixture",lease.token());session.disconnect();assertTrue(session.scopes().isEmpty());
    }
    @Test public void resourceKeysNeverCrossAccountGenerationsOrInjectHeaders()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture-one");DriveSession.Lease first=session.lease();
        first.rememberKey("file","key_123");assertEquals("file/key_123",first.resourceHeader("file"));
        assertNull(first.resourceHeader("other"));assertThrows(java.io.IOException.class,()->first.rememberKey("file","x\r\nAuthorization: value"));
        session.connect("fixture-two");assertNull(session.lease().resourceHeader("file"));assertThrows(DriveSession.AuthorizationRequired.class,()->first.resourceHeader("file"));
    }
    @Test public void readonlyLeaseCannotWriteAndOldWriteGrantDoesNotSurviveReconnect() throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture");
        assertThrows(java.io.IOException.class,()->session.lease().requireWrite());
        session.connect("fixture-write",true);DriveSession.Lease write=session.lease();write.requireWrite();
        session.connect("fixture-read");assertThrows(DriveSession.AuthorizationRequired.class,write::requireWrite);
        assertFalse(session.writable());assertThrows(java.io.IOException.class,()->session.lease().requireWrite());
    }
    @Test public void disconnectAndAccountChangeInvalidateInflightWork() throws Exception {
        DriveSession session = new DriveSession();
        assertThrows(DriveSession.AuthorizationRequired.class, session::lease);
        session.connect("test-token-one");
        DriveSession.Lease first = session.lease();
        session.connect("test-token-two");
        assertThrows(DriveSession.AuthorizationRequired.class, first::requireCurrent);
        DriveSession.Lease second = session.lease();
        session.invalidate(first);
        second.requireCurrent();
        session.disconnect();
        assertThrows(DriveSession.AuthorizationRequired.class, second::requireCurrent);
    }

    @Test public void malformedTokensCannotInjectHeaders() {
        DriveSession session = new DriveSession();
        for (String bad : new String[]{"", "a b", "a\rb", "a\nb", "a\nb\nc", "a\u0000b", null})
            assertThrows(IllegalArgumentException.class, () -> session.connect(bad));
    }
}
