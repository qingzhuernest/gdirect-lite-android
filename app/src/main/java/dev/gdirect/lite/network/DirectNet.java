package dev.gdirect.lite.network;

import dev.gdirect.lite.transfer.TransferRules;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** Public Google Drive HTTP requests only; normal HTTPS certificate validation stays enabled. */
public final class DirectNet {
    public interface Connections { HttpsURLConnection open(URL url, String ip) throws IOException; }
    public static volatile Connections connections = (url, ip) -> {
        if (ip != null) throw new IOException("Direct networking is not initialized");
        return (HttpsURLConnection) url.openConnection();
    };
    private DirectNet() {}

    /** Fixed public-download failure categories; never contains URLs or response bodies. */
    public static final class PublicFailure extends IOException {
        public final int status;
        public PublicFailure(int status) {
            this(status, "Google Drive 返回 HTTP " + status + "；请确认文件公开可访问");
        }
        private PublicFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Probe {
        public final URL finalUrl;
        public final long size;
        public final boolean ranges;
        public final String filename;
        public final String etag;
        public Probe(URL url, long size, boolean ranges, String filename, String etag) {
            this.finalUrl = url; this.size = size; this.ranges = ranges; this.filename = filename;
            this.etag = TransferRules.strongEtag(etag);
        }
    }

    public static String parseIp(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if(s.startsWith("[")&&s.endsWith("]"))s=s.substring(1,s.length()-1);
        return IpLiteral.canonical(s);
    }

    public static boolean googleHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("drive.google.com") || h.equals("drive.usercontent.google.com")
            || h.equals("www.googleapis.com") || h.equals("content.googleapis.com")
            || h.equals("drive.googleapis.com") || h.endsWith(".googleusercontent.com");
    }

    public static URL normalizeSharedLink(String input) throws IOException {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) throw new IOException("请先粘贴公开的 Google Drive 文件链接");
        URL url = new URL(raw);
        if (!url.getProtocol().equalsIgnoreCase("https") || !googleHost(url.getHost())
            || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null) {
            throw new IOException("仅接受 HTTPS Google Drive 文件链接");
        }
        String path = url.getPath();
        Matcher m = Pattern.compile("(?:/file/d/|/d/)([a-zA-Z0-9_-]{10,})").matcher(path);
        String id = m.find() ? m.group(1) : null;
        if (id == null) {
            m = Pattern.compile("(?:^|&)id=([a-zA-Z0-9_-]{10,})(?:&|$)")
                .matcher(url.getQuery() == null ? "" : url.getQuery());
            if (m.find()) id = m.group(1);
        }
        if (id == null && url.getHost().equalsIgnoreCase("drive.usercontent.google.com")
                && url.getPath().contains("download")) return url;
        if (id == null) throw new IOException("无法识别文件 ID；目前不支持文件夹及 Google 文档链接");
        String resource=null;
        if(url.getQuery()!=null)for(String part:url.getQuery().split("&")) {
            int separator=part.indexOf('=');
            if(separator>0&&URLDecoder.decode(part.substring(0,separator),"UTF-8").equalsIgnoreCase("resourcekey")) {
                String value=URLDecoder.decode(part.substring(separator+1),"UTF-8");
                if(!value.matches("[A-Za-z0-9_-]{1,200}")||(resource!=null&&!resource.equals(value)))throw new IOException("共享资源密钥无效或重复");
                resource=value;
            }
        }
        return new URL("https://drive.usercontent.google.com/download?id=" + id
            + "&export=download&confirm=t"+(resource==null?"":"&resourcekey="+resource));
    }

    public static HttpsURLConnection open(URL url, String ip, String range) throws IOException {
        return open(url, ip, range, null);
    }

    public static HttpsURLConnection open(URL url, String ip, String range, String validator) throws IOException {
        return open(url,ip,range,validator,connections);
    }
    public static HttpsURLConnection open(URL url,String ip,String range,String validator,Connections factory)throws IOException {
        for (int redirects = 0; redirects < 8; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !googleHost(url.getHost())
                    || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null) {
                throw new IOException("服务器跳转到了不允许的地址，已停止下载");
            }
            HttpsURLConnection connection = factory.open(url, ip);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "GDirectLite/0.1 Android");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            if (range != null) connection.setRequestProperty("Range", range);
            if (validator != null) connection.setRequestProperty("If-Range", validator);
            int code;
            try { code = connection.getResponseCode(); }
            catch (IOException error) { connection.disconnect(); throw error; }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Google 返回了无目标地址的跳转");
                url = new URL(url, location);
            } else {
                return connection;
            }
        }
        throw new IOException("跳转次数过多");
    }

    public static Probe probe(URL start, String ip) throws IOException {
        return probe(start,ip,connections);
    }
    public static Probe probe(URL start,String ip,Connections factory)throws IOException {
        URL url = start;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpsURLConnection connection = open(url, ip, "bytes=0-0",null,factory);
            try {
                int code = connection.getResponseCode();
                if (code != 200 && code != 206) {
                    throw new PublicFailure(code);
                }
                URL actual = connection.getURL();
                String disposition = connection.getHeaderField("Content-Disposition");
                String contentType = connection.getContentType();
                if (code == 200 && (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html"))
                    && (disposition == null || !disposition.toLowerCase(Locale.ROOT).contains("attachment"))) {
                    String page = readLimited(connection.getInputStream(), 48000);
                    URL confirmation = confirmationPage(actual, page);
                    if (confirmation == null) {
                        throw new PublicFailure(0, "Google 返回确认/登录网页；请检查共享权限或下载限额");
                    }
                    url = confirmation;
                    continue;
                }
                long size = connection.getContentLengthLong();
                boolean range = code == 206;
                if (range) {
                    size = TransferRules.rangeTotal(connection.getHeaderField("Content-Range"), 0, 0);
                }
                String name = getFilename(disposition, start);
                return new Probe(actual, size, range && size > 0, name, connection.getHeaderField("ETag"));
            } finally { connection.disconnect(); }
        }
        throw new PublicFailure(0, "Google 下载确认页面重复出现");
    }

    private static String readLimited(InputStream in, int limit) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[2048];
            int n;
            while (b.size() < limit && (n = stream.read(bytes, 0, Math.min(bytes.length, limit-b.size()))) != -1)
                b.write(bytes, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(name)
            + "\\s*=\\s*([\"'])(.*?)\\1").matcher(tag);
        if (m.find()) return m.group(2).replace("&amp;", "&");
        m = Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\s*=\\s*([^\\s>]+)").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static URL confirmationPage(URL base, String html) throws IOException {
        Matcher form = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>").matcher(html);
        while (form.find()) {
            String tag = form.group(1);
            if (!(tag.contains("download-form") || form.group(2).contains("confirm"))) continue;
            String action = attr(tag, "action");
            if (action == null) continue;
            StringBuilder extra = new StringBuilder();
            Matcher inputs = Pattern.compile("(?is)<input\\b[^>]*>").matcher(form.group(2));
            while (inputs.find()) {
                String key = attr(inputs.group(), "name");
                String value = attr(inputs.group(), "value");
                if (key != null && value != null) {
                    extra.append(extra.length() == 0 ? "" : "&")
                        .append(URLEncoder.encode(key, "UTF-8")).append("=")
                        .append(URLEncoder.encode(value, "UTF-8"));
                }
            }
            URL dest = new URL(base, action);
            return new URL(dest.toExternalForm() + (extra.length() == 0 ? "" :
                (dest.getQuery() == null ? "?" : "&") + extra));
        }
        return null;
    }

    private static String getFilename(String disposition, URL url) {
        String name = null;
        if (disposition != null) {
            Matcher m = Pattern.compile("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(disposition);
            if (m.find()) {
                try { name = URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception ignored) {}
            }
            if (name == null) {
                m = Pattern.compile("(?i)filename\\s*=\\s*[\"]?([^\";]+)").matcher(disposition);
                if (m.find()) name = m.group(1);
            }
        }
        if (name == null || name.trim().isEmpty()) {
            Matcher m = Pattern.compile("(?:^|&)id=([a-zA-Z0-9_-]+)").matcher(url.getQuery() == null ? "" : url.getQuery());
            name = m.find() ? "gdrive-" + m.group(1) + ".bin" : "gdrive-download.bin";
        }
        return TransferRules.safeFilename(name);
    }

}
