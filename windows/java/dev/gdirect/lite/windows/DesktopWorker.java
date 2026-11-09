package dev.gdirect.lite.windows;

import dev.gdirect.lite.drive.*;
import dev.gdirect.lite.transfer.FileIntegrity;
import dev.gdirect.lite.network.DirectNet;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Private parent/child pipes only. No listener, tokens in argv, or raw errors. */
public final class DesktopWorker {
    private final DesktopNetwork network=new DesktopNetwork();
    private final DriveSession session=new DriveSession();
    private String token;
    private final PrintWriter output=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
    private DesktopWorker()throws IOException{}
    private void emit(JSONObject json){output.println(json.toString());}
    public static void main(String[] args)throws Exception {
        DesktopWorker worker=new DesktopWorker();
        try(DataInputStream input=new DataInputStream(System.in)){
            while(true){int length;try{length=input.readInt();}catch(EOFException end){break;}
                if(length<2||length>1048576)break;
                byte[] data=input.readNBytes(length);if(data.length!=length)break;
                try{worker.emit(worker.execute(new JSONObject(new String(data,StandardCharsets.UTF_8))));}
                catch(Exception error){worker.emit(new JSONObject().put("error",safeFailure(error)));}
                finally{Arrays.fill(data,(byte)0);}
            }
        } finally{worker.session.disconnect();worker.network.close();}
    }
    static String safeFailure(Exception error){
        if(error instanceof DirectNet.PublicFailure){
            int status=((DirectNet.PublicFailure)error).status;
            if(status==404)return "公开文件不存在或无法公开访问（HTTP 404）。请向分享者确认文件和链接。";
            if(status==401||status==403)return "公开下载被拒绝（HTTP "+status+"）。请确认任何人可查看、允许下载，并检查下载限额。";
            if(status==429)return "Google 下载请求过多（HTTP 429），请稍后重试。";
            if(status==0)return "Google 返回登录或下载确认网页，未保存为文件。请检查公开权限或下载限额。";
            return "Google 公开下载返回 HTTP "+status+"，请稍后重试。";
        }
        if(error instanceof InvalidPublicLink)return "链接无效。请粘贴完整 HTTPS Google Drive 单文件分享链接；暂不支持文件夹和原生 Google 文档链接。";
        if(error instanceof FileAlreadyExistsException)return "保存位置已有同名文件，请选择新文件名。";
        if(error instanceof AccessDeniedException)return "无法写入保存位置，请选择有写入权限的文件夹。";
        if(error instanceof UnknownHostException||error instanceof ConnectException||error instanceof NoRouteToHostException)return "Google 数据网络连接失败，请检查网络后重试。";
        if(error instanceof DriveSession.AuthorizationRequired)return "登录凭据已失效，请重新授权。";
        if(error instanceof javax.net.ssl.SSLException||error instanceof java.security.cert.CertificateException)return "Google TLS 证书验证失败，请检查网络。";
        if(error instanceof java.io.InterruptedIOException)return "连接超时或已取消，请重试。";
        return "原项目 Java 核心未能完成请求，请检查网络、Drive API 权限或保存位置后重试。";
    }
    private static final class InvalidPublicLink extends IOException {}
    private static URL publicUrl(String link)throws InvalidPublicLink {
        try{if(link.length()>16384)throw new IOException();return DirectNet.normalizeSharedLink(link);}
        catch(IOException|IllegalArgumentException error){throw new InvalidPublicLink();}
    }
    private JSONObject execute(JSONObject request)throws Exception {
        String op=request.getString("op");
        if(op.equals("check")){network.check();return new JSONObject().put("ok",true);}
        if(op.equals("publicInfo")){
            String link=request.getString("link");
            DirectNet.Probe info=DirectNet.probe(publicUrl(link),null,(url,ip)->network.open(url));
            return new JSONObject().put("name",info.filename).put("size",info.size);
        }
        if(op.equals("public")){
            String link=request.getString("link");
            URL start=publicUrl(link);
            DirectNet.Connections factory=(url,ip)->network.open(url);
            DirectNet.Probe probe=DirectNet.probe(start,null,factory);
            Path target=Paths.get(request.getString("destination")).toAbsolutePath();
            if(Files.exists(target))throw new IOException("Destination exists");
            Path temp=temporary(request,target);
            HttpsURLConnection c=null;
            try{
                c=DirectNet.open(probe.finalUrl,null,null,null,factory);
                if(c.getResponseCode()!=200)throw new DirectNet.PublicFailure(c.getResponseCode());
                String type=c.getContentType(),disposition=c.getHeaderField("Content-Disposition");
                if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html")&&(disposition==null||!disposition.toLowerCase(Locale.ROOT).contains("attachment")))throw new DirectNet.PublicFailure(0);
                long length=c.getContentLengthLong(),count=0,last=0;
                try(InputStream in=c.getInputStream();OutputStream out=Files.newOutputStream(temp)){
                    byte[] buffer=new byte[81920];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);count+=n;if(System.nanoTime()-last>100000000){emit(new JSONObject().put("progress",count));last=System.nanoTime();}}
                }
                if((length>=0&&count!=length)||(probe.size>=0&&count!=probe.size))throw new IOException("Public size mismatch");
                Files.move(temp,target);return new JSONObject().put("ok",true).put("bytes",count);
            }finally{if(c!=null)c.disconnect();Files.deleteIfExists(temp);}
        }
        if(op.equals("oauth")){
            // Endpoints from downloaded configs are never followed.
            HttpsURLConnection c=network.open(new URL("https://oauth2.googleapis.com/token"));
            try{
                c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                byte[] body=request.getString("form").getBytes(StandardCharsets.UTF_8);
                try(OutputStream out=c.getOutputStream()){out.write(body);}finally{Arrays.fill(body,(byte)0);}
                int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
                return new JSONObject().put("status",status).put("body",bounded(stream,65536));
            } finally{c.disconnect();}
        }
        String current=request.getString("token");
        if(!current.equals(token)||!session.connected()){session.connect(current,Collections.singleton(DriveApi.READONLY_SCOPE),false);token=current;}
        DriveTransport transport=new DriveTransport(session.lease(),network::open,()->session.disconnect());
        DriveApi api=new DriveApi(transport);
        if(op.equals("list")){
            String search=request.optString("search","");
            DriveApi.Page page=api.list(request.optString("folder","root"),search,request.optString("page",""),search.isEmpty()?DriveApi.View.FOLDER:DriveApi.View.ALL,DriveApi.Sort.NAME);
            JSONArray files=new JSONArray();
            for(DriveApi.FileEntry file:page.files)files.put(new JSONObject().put("Id",file.id).put("Name",file.name).put("MimeType",file.mime).put("Size",file.size<0?JSONObject.NULL:file.size).put("Md5",file.md5).put("Modified",file.modified).put("CanDownload",file.downloadable));
            return new JSONObject().put("Files",files).put("Next",page.nextToken.isEmpty()?JSONObject.NULL:page.nextToken);
        }
        if(op.equals("download")){
            DriveApi.FileEntry file=api.resolve(api.metadata(request.getString("id")));
            if(!file.downloadable)throw new IOException("Download unavailable");
            URL url=file.exportMime.isEmpty()?api.mediaUrl(file.id):api.exportUrl(file);
            Path target=Paths.get(request.getString("destination")).toAbsolutePath();
            if(Files.exists(target))throw new IOException("Destination exists");
            Path temp=temporary(request,target);
            HttpsURLConnection c=null;
            try {
                c=transport.open(url);long length=c.getContentLengthLong();long count=0;MessageDigest md5=MessageDigest.getInstance("MD5");
                try(InputStream in=c.getInputStream();OutputStream out=Files.newOutputStream(temp)){
                    byte[] buffer=new byte[81920];int n;long last=0;
                    while((n=in.read(buffer))!=-1){session.lease().requireCurrent();out.write(buffer,0,n);md5.update(buffer,0,n);count+=n;
                        if(System.nanoTime()-last>100000000){emit(new JSONObject().put("progress",count));last=System.nanoTime();}}
                }
                if(length>=0&&count!=length)throw new IOException("Length mismatch");
                if(file.exportMime.isEmpty())FileIntegrity.verify(count,file.size>=0?file.size:length>=0?length:count,md5.digest(),file.md5);
                Files.move(temp,target);return new JSONObject().put("ok",true).put("bytes",count);
            }finally{if(c!=null)c.disconnect();Files.deleteIfExists(temp);}
        }
        throw new IOException("Unsupported operation");
    }
    private static String bounded(InputStream stream,int limit)throws IOException {
        if(stream==null)return "";
        try(stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[8192];int n;while((n=stream.read(block))!=-1){if(out.size()+n>limit)throw new IOException("Response too large");out.write(block,0,n);}return out.toString(StandardCharsets.UTF_8);
        }
    }
    private static Path temporary(JSONObject request,Path target)throws Exception{
        Path temp=Paths.get(request.getString("temporary")).toAbsolutePath().normalize();
        if(!temp.getParent().equals(target.normalize().getParent())||temp.equals(target.normalize())||!temp.getFileName().toString().endsWith(".part"))throw new IOException("Temporary destination rejected");
        return Files.createFile(temp);
    }
}
