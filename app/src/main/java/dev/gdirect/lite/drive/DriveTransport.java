package dev.gdirect.lite.drive;

import dev.gdirect.lite.network.ReadRetry;
import dev.gdirect.lite.transfer.TransferRules;
import dev.gdirect.lite.transfer.UploadTransfer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Credentials stay on the API. Media redirects use validated Google content origins without credentials. */
public final class DriveTransport {
    public interface Connections { HttpsURLConnection open(URL url) throws IOException; }
    private final DriveSession.Lease lease;
    private final Connections connections;
    private final java.util.function.Consumer<String> unauthorized;

    public DriveTransport(DriveSession.Lease lease, Connections connections, Runnable unauthorized) {
        this(lease,connections,token->unauthorized.run());
    }
    public DriveTransport(DriveSession.Lease lease,Connections connections,java.util.function.Consumer<String> unauthorized){
        this.lease = lease; this.connections = connections; this.unauthorized = unauthorized;
    }

    public static void requireApiUrl(URL url) throws IOException {
        if (!"https".equals(url.getProtocol()) || !"www.googleapis.com".equals(url.getHost())
            || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null
            || url.getRef() != null || !(url.getPath().matches("/drive/v3/files(?:/[A-Za-z0-9_-]{1,200}(?:/export|/permissions(?:/[A-Za-z0-9_-]{1,200})?)?)?")||url.getPath().matches("/(?:resumable/)?upload/drive/v3/files")||url.getPath().equals("/drive/v3/drives")))
            throw new IOException("已阻止向 Google Drive API 以外的地址发送授权信息");
    }

    public HttpsURLConnection open(URL url) throws IOException {
        String[] segments=url.getPath().split("/");
        return open(url,segments.length>=5&&segments[3].equals("files")?segments[4]:null);
    }
    private HttpsURLConnection open(URL url,String resourceId)throws IOException {
        return open(url,resourceId,null,null);
    }
    public HttpsURLConnection openRange(URL url,String range,String validator)throws IOException {
        if(range==null||!range.matches("bytes=[0-9]+-[0-9]+")||(validator!=null&&TransferRules.strongEtag(validator)==null))throw new IOException("分块请求范围或版本无效");
        String[] segments=url.getPath().split("/");return open(url,segments.length>=5?segments[4]:null,range,validator);
    }
    private HttpsURLConnection open(URL url,String resourceId,String range,String validator)throws IOException {
        return open(url,resourceId,range,validator,0);
    }
    private HttpsURLConnection open(URL url,String resourceId,String range,String validator,int tokenRetry)throws IOException {
        requireApiUrl(url);
        lease.requireCurrent();
        HttpsURLConnection c = connections.open(url);
        try {
            String token = lease.token();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            String resource=lease.resourceHeader(resourceId);if(resource!=null)c.setRequestProperty("X-Goog-Drive-Resource-Keys",resource);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "GDirectLite/0.2 Android");
            if(range!=null)c.setRequestProperty("Range",range);if(validator!=null)c.setRequestProperty("If-Range",validator);
            int code = c.getResponseCode();
            boolean content=false;
            for(int hop=0;code==301||code==302||code==303||code==307||code==308;hop++) {
                if(hop>=4||!mediaRequest(url))throw new IOException("已阻止非文件内容请求的跳转");
                String location=c.getHeaderField("Location");
                if(location==null||location.length()>16384)throw new IOException("文件内容跳转地址无效");
                URL next=new URL(c.getURL(),location);requireContentUrl(next);lease.requireCurrent();
                c.disconnect();c=connections.open(next);content=true;
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);
                // Deliberately rebuild a minimal request. No bearer, resource key, cookie or Referer.
                c.setRequestProperty("Accept-Encoding","identity");
                if(range!=null)c.setRequestProperty("Range",range);if(validator!=null)c.setRequestProperty("If-Range",validator);
                lease.requireCurrent();code=c.getResponseCode();
            }
            lease.requireCurrent();
            if (code == 401) {
                if(!content){
                    if(tokenRetry==0&&!token.equals(lease.renewalToken())){c.disconnect();return open(url,resourceId,range,validator,1);}
                    unauthorized.accept(token);throw new DriveSession.AuthorizationRequired();
                }
                throw new IOException("文件内容链接已过期，请重新下载");
            }
            if (code == 403) throw new IOException("Google 拒绝访问：请检查文件下载权限、Drive API 配置或配额");
            if (code == 404) throw new IOException("文件不存在，或当前 Google 账号无权访问");
            if (code == 429 || code == 500 || code == 502 || code == 503 || code == 504)
                throw new ReadRetry.Unavailable(code,c.getHeaderField("Retry-After"));
            if (code >= 300 && code < 400) throw new IOException("私人文件请求返回跳转；为保护令牌已停止，请稍后重试");
            if (code != 200 && !(range!=null&&code==206)) throw new IOException("Google Drive 请求失败（HTTP " + code + "），请稍后重试");
            return c;
        } catch (IOException | RuntimeException error) { c.disconnect(); throw error; }
    }

    private static boolean mediaRequest(URL url) {
        return url.getPath().matches("/drive/v3/files/[A-Za-z0-9_-]{1,200}/export")
            ||(url.getPath().matches("/drive/v3/files/[A-Za-z0-9_-]{1,200}")&&url.getQuery()!=null
                &&java.util.Arrays.asList(url.getQuery().split("&")).contains("alt=media"));
    }
    public static void requireContentUrl(URL url)throws IOException {
        String host=url.getHost();
        if(!"https".equals(url.getProtocol())||(url.getPort()!=-1&&url.getPort()!=443)||url.getUserInfo()!=null||url.getRef()!=null
            ||!(host.equals("drive.usercontent.google.com")||(host.endsWith(".googleusercontent.com")&&host.length()>".googleusercontent.com".length())))
            throw new IOException("已阻止不可信的文件内容跳转");
    }

    public String json(URL url) throws IOException {
        return ReadRetry.run(() -> jsonOnce(url),lease::requireCurrent);
    }
    public String json(URL url,String resourceId)throws IOException{return ReadRetry.run(()->jsonOnce(url,resourceId),lease::requireCurrent);}
    public void rememberKey(String id,String key)throws IOException{lease.rememberKey(id,key);}

    public String mutate(URL url,String method,String json) throws IOException {
        return mutate(url,method,json,null);
    }
    public String mutate(URL url,String method,String json,String expectedEtag)throws IOException {
        requireApiUrl(url); lease.requireWrite();
        if(!url.getPath().startsWith("/drive/v3/files"))throw new IOException("不允许的文件操作地址");
        if(!method.equals("POST")&&!method.equals("PATCH")&&!method.equals("DELETE"))throw new IOException("不支持的文件操作");
        byte[] body=json.getBytes(StandardCharsets.UTF_8);
        if(body.length>1024*1024)throw new IOException("文件操作信息过大");
        HttpsURLConnection c=connections.open(url);
        try {
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);
            String token=lease.token();c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
            if(expectedEtag!=null){String strong=TransferRules.strongEtag(expectedEtag);if(strong==null)throw new IOException("文件版本标识无效");c.setRequestProperty("If-Match",strong);}
            String[] parts=url.getPath().split("/");String resource=lease.resourceHeader(parts.length>=5?parts[4]:null);if(resource!=null)c.setRequestProperty("X-Goog-Drive-Resource-Keys",resource);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            if(!method.equals("DELETE")) {
                c.setDoOutput(true);
                try(java.io.OutputStream out=c.getOutputStream()){out.write(body);}
            }
            int code=c.getResponseCode();lease.requireCurrent();
            if(code==401){unauthorized.accept(token);throw new DriveSession.AuthorizationRequired();}
            if(code==403)throw new IOException("没有执行此文件操作的权限");
            if(code==404)throw new IOException("文件不存在或已无权访问");
            if(code==412)throw new IOException("文件状态已经改变，请刷新后重新确认操作");
            if(code>=300&&code<400)throw new IOException("文件操作返回跳转，已保护授权信息并停止");
            if(code!=200&&code!=201&&code!=204)throw new IOException("文件操作未确认成功（HTTP "+code+"），请刷新列表核对后再决定是否重试");
            if(code==204)return "{}";
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){lease.requireCurrent();if(out.size()+n>1024*1024)throw new IOException("文件操作响应过大，请刷新核对");out.write(buffer,0,n);}
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { c.disconnect(); }
    }
    public static final class Document {
        public final String body,etag;public Document(String body,String etag){this.body=body;this.etag=etag;}
    }
    public Document document(URL url)throws IOException {
        return ReadRetry.run(()->{HttpsURLConnection c=open(url);String etag=c.getHeaderField("ETag");return new Document(readJson(c),etag);},lease::requireCurrent);
    }

    public static void requireUploadSession(URL url)throws IOException {
        requireApiUrl(url);
        if(!url.getPath().matches("/(?:resumable/)?upload/drive/v3/files")||url.getQuery()==null||!url.getQuery().matches("(?:.*&)?upload_id=[^&]+(?:&.*)?"))
            throw new IOException("无效的上传会话地址");
    }
    public URL startUpload(String json,String mime,long size)throws IOException {
        lease.requireWrite();
        if(size<1||!mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))throw new IOException("上传类型或大小无效");
        URL url=new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=id,name,mimeType,size,md5Checksum,capabilities/canDownload");
        HttpsURLConnection c=connections.open(url);
        try {
            String token=prepareUpload(c,"POST","application/json; charset=utf-8");
            c.setRequestProperty("X-Upload-Content-Type",mime);c.setRequestProperty("X-Upload-Content-Length",Long.toString(size));
            try(java.io.OutputStream out=c.getOutputStream()){out.write(json.getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode();lease.requireCurrent();
            if(code==401){unauthorized.accept(token);throw new DriveSession.AuthorizationRequired();}
            if(code!=200&&code!=201)throw new IOException("无法建立上传会话（HTTP "+code+"）");
            String location=c.getHeaderField("Location");
            if(location==null)throw new IOException("Google 未返回上传会话");
            URL session;
            try{session=new URL(location);}catch(java.net.MalformedURLException e){throw new IOException("Google 返回了无效的上传会话地址");}
            requireUploadSession(session);return session;
        } finally {c.disconnect();}
    }
    private String prepareUpload(HttpsURLConnection c,String method,String mime)throws IOException {
        lease.requireWrite();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(30000);
        String token=lease.token();c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
        c.setRequestProperty("Content-Type",mime);c.setDoOutput(true);
        return token;
    }
    public UploadTransfer.Reply uploadChunk(URL session,String mime,long start,byte[] bytes,long total)throws IOException {
        requireUploadSession(session);lease.requireWrite();
        if(total<1||start<0||start>total||bytes.length>UploadTransfer.CHUNK||bytes.length>total-start)throw new IOException("上传块范围无效");
        HttpsURLConnection c=connections.open(session);
        try {
            String token=prepareUpload(c,"PUT",mime);
            c.setRequestProperty("Content-Range",bytes.length==0?"bytes */"+total:"bytes "+start+"-"+(start+bytes.length-1)+"/"+total);
            try(java.io.OutputStream out=c.getOutputStream()){out.write(bytes);}
            int code=c.getResponseCode();lease.requireCurrent();
            if(code==401){unauthorized.accept(token);throw new DriveSession.AuthorizationRequired();}
            if(code==308) {
                String range=c.getHeaderField("Range");long offset=0;
                if(range!=null) {
                    if(!range.matches("bytes=0-[0-9]+"))throw new IOException("上传确认范围无效");
                    try{offset=Math.addExact(Long.parseLong(range.substring(8)),1);}catch(RuntimeException e){throw new IOException("上传确认范围溢出");}
                }
                return new UploadTransfer.Reply(offset,null);
            }
            if(code==429||code==500||code==502||code==503||code==504)throw new ReadRetry.Unavailable(code,c.getHeaderField("Retry-After"));
            if(code!=200&&code!=201)throw new UploadTransfer.Rejected("上传未确认成功（HTTP "+code+"），请刷新列表核对");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){lease.requireCurrent();if(out.size()+n>1024*1024)throw new IOException("上传响应过大");out.write(b,0,n);}
                return new UploadTransfer.Reply(total,out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {c.disconnect();}
    }

    private String jsonOnce(URL url) throws IOException {
        HttpsURLConnection c = open(url);
        return readJson(c);
    }
    private String jsonOnce(URL url,String resourceId)throws IOException{return readJson(open(url,resourceId));}
    private String readJson(HttpsURLConnection c)throws IOException {
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                lease.requireCurrent();
                if (out.size() + n > 1024 * 1024) throw new IOException("Google 返回的文件列表过大，请缩小搜索范围");
                out.write(buffer, 0, n);
            }
            lease.requireCurrent();
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }
}
