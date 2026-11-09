package dev.gdirect.lite.windows;
import dev.gdirect.lite.network.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class WindowsAdapterTest {
    @Test public void sharesOriginalPublicLinkParser()throws Exception{
        assertTrue(DirectNet.normalizeSharedLink("https://drive.google.com/file/d/abcdefghijk/view").toString().contains("id=abcdefghijk"));
        assertTrue(DirectNet.normalizeSharedLink("https://drive.google.com/open?id=abcdefghijk&resourcekey=abc_123").toString().contains("resourcekey=abc_123"));
    }
    @Test public void publicLinksCannotBecomeOAuthRequests()throws Exception{
        assertFalse(DirectNet.googleHost("oauth2.googleapis.com"));
        try{DirectNet.normalizeSharedLink("https://oauth2.googleapis.com/file/d/abcdefghijk");fail();}catch(java.io.IOException expected){}
    }
    @Test public void privateExceptionTextIsNeverReturned(){
        assertFalse(DesktopWorker.safeFailure(new java.io.IOException("private-name token-secret https://private.example")).contains("token-secret"));
    }
    @Test public void platformAdapterRejectsUnknownOriginBeforeConnecting()throws Exception{
        DesktopNetwork network=new DesktopNetwork();
        try{try{network.open(new java.net.URL("https://example.com/token"));fail();}catch(java.io.IOException expected){}}
        finally{network.close();}
    }
}
