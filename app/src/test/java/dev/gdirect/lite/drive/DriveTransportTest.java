package dev.gdirect.lite.drive;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Test;

public final class DriveTransportTest {
    @Test public void authenticatedRangeKeepsCredentialsOnTheApiAndRejectsWeakValidators()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture");URL url=new URL("https://www.googleapis.com/drive/v3/files/file?alt=media");
        AtomicInteger calls=new AtomicInteger();Fake c=new Fake(url,206);
        DriveTransport transport=new DriveTransport(session.lease(),target->{calls.incrementAndGet();return c;},()->{});
        assertSame(c,transport.openRange(url,"bytes=0-1023","\"version\""));
        assertEquals("Bearer fixture",c.getRequestProperty("Authorization"));assertEquals("bytes=0-1023",c.getRequestProperty("Range"));assertEquals("\"version\"",c.getRequestProperty("If-Range"));
        assertThrows(IOException.class,()->transport.openRange(url,"bytes=0-1","W/\"weak\""));
        assertThrows(IOException.class,()->transport.openRange(new URL("https://evil.example/file"),"bytes=0-1",null));
        assertEquals(1,calls.get());
    }
    static final class Fake extends HttpsURLConnection {
        final int code;
        boolean closed;
        byte[] body = "{\"files\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String retryAfter;
        String uploadLocation,uploadRange;
        String etag;
        Runnable onResponse=()->{};
        final java.io.ByteArrayOutputStream written=new java.io.ByteArrayOutputStream();
        Fake(URL url, int code) { super(url); this.code = code; }
        @Override public int getResponseCode() { onResponse.run();return code; }
        @Override public void setRequestMethod(String value) { method=value; }
        @Override public java.io.OutputStream getOutputStream(){return written;}
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public String getHeaderField(String name) { return "ETag".equals(name)?etag:("Retry-After".equals(name) ? retryAfter : ("Range".equals(name)?uploadRange:("Location".equals(name)?(uploadLocation==null?"https://example.net/steal":uploadLocation):null))); }
        @Override public void disconnect() { closed = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "TEST-ONLY"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }

    @Test public void aLateOldToken401RetriesAReadWithTheAlreadyRenewedToken()throws Exception {
        DriveSession s=new DriveSession();s.connect("old");DriveSession.Lease lease=s.lease();
        URL url=new URL("https://www.googleapis.com/drive/v3/files");Fake first=new Fake(url,401),second=new Fake(url,200);
        first.onResponse=()->assertTrue(s.renew(lease,"new",java.util.Set.of(DriveApi.READONLY_SCOPE),false));
        AtomicInteger calls=new AtomicInteger();DriveTransport transport=new DriveTransport(lease,u->calls.incrementAndGet()==1?first:second,rejected->s.invalidate(lease,rejected));
        assertSame(second,transport.open(url));assertEquals(2,calls.get());assertTrue(first.closed);
        assertEquals("Bearer old",first.getRequestProperty("Authorization"));assertEquals("Bearer new",second.getRequestProperty("Authorization"));assertTrue(s.connected());
    }
    @Test public void tokenRotationDoesNotReplayAWritingRequestOrRejectFreshCredentials()throws Exception {
        DriveSession s=new DriveSession();s.connect("old",true);DriveSession.Lease lease=s.lease();
        URL url=new URL("https://www.googleapis.com/drive/v3/files/file");Fake reply=new Fake(url,401);
        reply.onResponse=()->assertTrue(s.renew(lease,"new",java.util.Set.of(DriveApi.MANAGE_SCOPE),true));
        AtomicInteger calls=new AtomicInteger();DriveTransport transport=new DriveTransport(lease,u->{calls.incrementAndGet();return reply;},rejected->s.invalidate(lease,rejected));
        assertThrows(DriveSession.AuthorizationRequired.class,()->transport.mutate(url,"PATCH","{}"));
        assertEquals(1,calls.get());assertEquals("new",lease.token());
    }
    @Test public void requestUsesCredentialsRefreshedByThePlatformOpener()throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();DriveSession s=new DriveSession(clock::get);s.connect("old");DriveSession.Lease lease=s.lease();
        clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(51));URL url=new URL("https://www.googleapis.com/drive/v3/files");Fake reply=new Fake(url,200);
        DriveTransport transport=new DriveTransport(lease,u->{assertTrue(s.renew(lease,"fresh",java.util.Set.of(DriveApi.READONLY_SCOPE),false));return reply;},()->fail());
        transport.open(url);assertEquals("Bearer fresh",reply.getRequestProperty("Authorization"));
    }
    @Test public void credentialFreeContentRedirectCanFinishAfterCredentialDeadline()throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();DriveSession s=new DriveSession(clock::get);s.connect("token");DriveSession.Lease lease=s.lease();
        URL api=new URL("https://www.googleapis.com/drive/v3/files/file?alt=media"),content=new URL("https://drive.usercontent.google.com/download?id=file");
        Fake redirect=new Fake(api,302),body=new Fake(content,200);redirect.uploadLocation=content.toString();
        redirect.onResponse=()->clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(51));
        DriveTransport transport=new DriveTransport(lease,u->{lease.requireCurrent();if(u.getHost().equals("www.googleapis.com"))lease.token();return u.getHost().equals("www.googleapis.com")?redirect:body;},()->fail());
        assertSame(body,transport.open(api));assertNull(body.getRequestProperty("Authorization"));lease.requireCurrent();
    }

    @Test public void nonApiDestinationsAreRejectedBeforeOpening() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        AtomicInteger opened = new AtomicInteger();
        DriveTransport transport = new DriveTransport(session.lease(), url -> { opened.incrementAndGet(); return new Fake(url,200); }, () -> {});
        for (String bad : new String[]{"http://www.googleapis.com/drive/v3/files", "https://www.googleapis.com.example.net/drive/v3/files", "https://drive.google.com/drive/v3/files", "https://8.8.8.8/drive/v3/files", "https://user@www.googleapis.com/drive/v3/files", "https://www.googleapis.com:444/drive/v3/files", "https://www.googleapis.com/other", "https://www.googleapis.com/drive/v3/files#fragment"})
            assertThrows(IOException.class, () -> transport.open(new URL(bad)));
        assertEquals(0, opened.get());
    }

    @Test public void redirectsNeverReceiveASecondAuthenticatedRequest() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        for (int code : new int[]{301,302,303,307,308}) {
            Fake c = new Fake(url,code);
            AtomicInteger opened = new AtomicInteger();
            DriveTransport transport = new DriveTransport(session.lease(), target -> {opened.incrementAndGet(); return c;}, () -> {});
            assertThrows(IOException.class, () -> transport.open(url));
            assertEquals(1, opened.get());
            assertFalse(c.getInstanceFollowRedirects());
            assertTrue(c.closed);
        }
    }

    @Test public void unauthorizedInvalidatesSessionWithoutLeakingResponse() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        DriveSession.Lease lease = session.lease();
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        Fake c = new Fake(url,401);
        DriveTransport transport = new DriveTransport(lease, ignored -> c, () -> session.invalidate(lease));
        IOException error = assertThrows(DriveSession.AuthorizationRequired.class, () -> transport.open(url));
        assertFalse(error.getMessage().contains("unit-test-token"));
        assertFalse(session.connected());
        assertTrue(c.closed);
    }

    @Test public void validApiReceivesHeaderAndBoundedJson() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        Fake c = new Fake(url,200);
        DriveTransport transport = new DriveTransport(session.lease(), ignored -> c, () -> {});
        assertEquals("{\"files\":[]}", transport.json(url));
        assertEquals("Bearer unit-test-token", c.getRequestProperty("Authorization"));
        assertTrue(c.closed);
        c.body = new byte[1024*1024+1];
        assertThrows(IOException.class, () -> transport.json(url));
    }

    @Test public void throttledReadRetriesWithoutFollowingRedirectsOrChangingCredentials() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        AtomicInteger calls = new AtomicInteger();
        java.util.List<Fake> connections = new java.util.ArrayList<>();
        DriveTransport transport = new DriveTransport(session.lease(), target -> {
            Fake c = new Fake(target,calls.incrementAndGet() < 3 ? 429 : 200);
            c.retryAfter="0"; connections.add(c); return c;
        }, () -> fail("must not invalidate a valid session"));
        assertEquals("{\"files\":[]}",transport.json(url));
        assertEquals(3,calls.get());
        for(Fake c:connections) {
            assertEquals("Bearer unit-test-token",c.getRequestProperty("Authorization"));
            assertFalse(c.getInstanceFollowRedirects()); assertTrue(c.closed);
        }
    }

    @Test public void writesRequireExplicitGrantAndAreNotRetriedOnUncertainFailure() throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture");AtomicInteger calls=new AtomicInteger();
        URL url=new URL("https://www.googleapis.com/drive/v3/files/file");
        Fake c=new Fake(url,503);
        DriveTransport readonly=new DriveTransport(session.lease(),target->{calls.incrementAndGet();return c;},()->{});
        assertThrows(IOException.class,()->readonly.mutate(url,"PATCH","{}"));assertEquals(0,calls.get());
        session.connect("fixture-write",true);
        DriveTransport write=new DriveTransport(session.lease(),target->{calls.incrementAndGet();return c;},()->{});
        assertThrows(IOException.class,()->write.mutate(url,"PATCH","{\"name\":\"new\"}"));
        assertEquals(1,calls.get());assertEquals("PATCH",c.getRequestMethod());assertFalse(c.getInstanceFollowRedirects());assertTrue(c.closed);
        assertEquals("{\"name\":\"new\"}",c.written.toString("UTF-8"));
    }
    @Test public void uploadLocationMustStayOnGoogleAndCommittedRangeIsParsed()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",true);
        URL url=new URL("https://www.googleapis.com/upload/drive/v3/files?upload_id=fixture");
        DriveTransport.requireUploadSession(new URL("https://www.googleapis.com/resumable/upload/drive/v3/files?upload_id=fixture"));
        Fake init=new Fake(url,200);DriveTransport rejected=new DriveTransport(session.lease(),u->init,()->{});
        assertThrows(IOException.class,()->rejected.startUpload("{}","text/plain",3));assertTrue(init.closed);
        Fake chunk=new Fake(url,308);chunk.uploadRange="bytes=0-2";
        DriveTransport transport=new DriveTransport(session.lease(),u->chunk,()->{});
        assertEquals(3,transport.uploadChunk(url,"text/plain",0,new byte[]{1,2,3},3).committed);
        assertEquals("bytes 0-2/3",chunk.getRequestProperty("Content-Range"));assertFalse(chunk.getInstanceFollowRedirects());
        for(String bad:new String[]{"https://evil.example/upload/drive/v3/files?upload_id=x","https://www.googleapis.com/drive/v3/files?upload_id=x","https://www.googleapis.com/upload/drive/v3/files"})
            assertThrows(IOException.class,()->DriveTransport.requireUploadSession(new URL(bad)));
    }
    @Test public void successfulMetadataWriteIsBoundedAndRedirectsCannotReceiveCredentials() throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",true);
        URL url=new URL("https://www.googleapis.com/drive/v3/files/file");
        Fake c=new Fake(url,200);DriveTransport transport=new DriveTransport(session.lease(),ignored->c,()->{});
        assertEquals("{\"files\":[]}",transport.mutate(url,"PATCH","{}"));
        assertThrows(IOException.class,()->transport.mutate(new URL("https://example.net/drive/v3/files/file"),"PATCH","{}"));
        assertThrows(IOException.class,()->transport.mutate(url,"PATCH","x".repeat(1024*1024+1)));
    }
}
