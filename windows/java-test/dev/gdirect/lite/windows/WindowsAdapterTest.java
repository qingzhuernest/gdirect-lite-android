package dev.gdirect.lite.windows;
import dev.gdirect.lite.network.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class WindowsAdapterTest {
    @Test public void publicHttpErrorsSurviveCoreAndRemainSanitized()throws Exception{
        for(int status:new int[]{401,403,404,429,503}){
            try{DirectNet.probe(new java.net.URL("https://drive.usercontent.google.com/download?id=private-id"),null,(url,ip)->new PublicResponse(url,status,"application/octet-stream"));fail();}
            catch(DirectNet.PublicFailure error){
                assertEquals(status,error.status);
                String message=DesktopWorker.safeFailure(error);
                assertTrue(message.contains(Integer.toString(status)));
                assertFalse(message.contains("private-id"));
                assertFalse(message.contains("token-secret"));
            }
        }
    }
    @Test public void publicHtmlIsNotMistakenForAFile()throws Exception{
        try{DirectNet.probe(new java.net.URL("https://drive.usercontent.google.com/download?id=private-id"),null,(url,ip)->new PublicResponse(url,200,"text/html"));fail();}
        catch(DirectNet.PublicFailure error){assertEquals(0,error.status);assertTrue(DesktopWorker.safeFailure(error).contains("确认网页"));}
    }
    private static final class PublicResponse extends javax.net.ssl.HttpsURLConnection {
        private final int status; private final String type;
        PublicResponse(java.net.URL url,int status,String type){super(url);this.status=status;this.type=type;}
        public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
        public int getResponseCode(){return status;} public String getContentType(){return type;}
        public String getHeaderField(String name){return null;}
        public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream("<html>private-id token-secret</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public String getCipherSuite(){return "fixture";}
        public java.security.cert.Certificate[] getLocalCertificates(){return null;}
        public java.security.cert.Certificate[] getServerCertificates(){return null;}
    }
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
