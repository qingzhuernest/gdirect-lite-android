using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace GDirect;

public sealed record DesktopConfig(string ClientId, string ClientSecret)
{
    public static DesktopConfig Load(string path) {
        using var stream=File.OpenRead(path);
        if(stream.Length>65536)throw new InvalidDataException("授权配置文件超过大小上限，请选择 Google 下载的原始 JSON。");
        using var doc = JsonDocument.Parse(stream);
        if (!doc.RootElement.TryGetProperty("installed", out var app)) throw new InvalidDataException("请选择桌面应用 OAuth JSON，不能使用 Android 或 Web 客户端。");
        var id = app.GetProperty("client_id").GetString() ?? "";
        if (!id.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal) || id.Length > 512) throw new InvalidDataException("客户端 ID 无效。");
        return new(id, app.TryGetProperty("client_secret", out var secret) ? secret.GetString() ?? "" : "");
    }
}
public sealed class OAuthSession
{
    private string? access, refresh;
    private DateTimeOffset expiry;
    private readonly GoogleTransport transport;
    private readonly DesktopConfig config;
    public OAuthSession(GoogleTransport transport, DesktopConfig config) { this.transport = transport; this.config = config; }
    public Action<DesktopProfile>? ProfileChanged {get;set;}
    public static OAuthSession Restore(GoogleTransport transport,DesktopProfile profile){
        if(profile.RefreshToken.Length is 0 or >16384||!profile.Configuration.ClientId.EndsWith(".apps.googleusercontent.com",StringComparison.Ordinal))throw new InvalidDataException("Saved profile rejected");
        return new OAuthSession(transport,profile.Configuration){refresh=profile.RefreshToken};
    }
    public static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    public static string Challenge(string verifier) => Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
    public static Dictionary<string,string> ParseQuery(string query) {
        var result = new Dictionary<string,string>();
        foreach (var field in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)) {
            var pair = field.Split('=', 2);
            var key = Uri.UnescapeDataString(pair[0].Replace('+', ' '));
            if (!result.TryAdd(key, pair.Length == 2 ? Uri.UnescapeDataString(pair[1].Replace('+',' ')) : "")) throw new InvalidDataException("授权回调包含重复字段。");
        }
        return result;
    }
    public static string CallbackCode(string target, string state) {
        if (!target.StartsWith("/?", StringComparison.Ordinal)) throw new InvalidDataException("授权回调路径不匹配。");
        var query = ParseQuery(target[2..]);
        if (!query.TryGetValue("state", out var actual) || !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(state), Encoding.UTF8.GetBytes(actual))) throw new InvalidDataException("授权回调校验失败，请重新登录。");
        if (query.ContainsKey("error")) throw new InvalidDataException("Google 未授予访问权限。");
        if (!query.TryGetValue("code", out var code) || code.Length is 0 or > 4096) throw new InvalidDataException("授权码缺失。");
        return code;
    }
    public async Task Login(CancellationToken ct) {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromMinutes(3)); ct = deadline.Token;
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var state = Base64Url(RandomNumberGenerator.GetBytes(32));
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(1);
        try {
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            var redirect = $"http://127.0.0.1:{port}/";
            var fields = new Dictionary<string,string> { ["client_id"] = config.ClientId, ["redirect_uri"] = redirect,
                ["response_type"] = "code", ["scope"] = "https://www.googleapis.com/auth/drive.readonly",
                ["state"] = state, ["code_challenge"] = Challenge(verifier), ["code_challenge_method"] = "S256", ["access_type"] = "offline", ["prompt"]="consent" };
            var url = "https://accounts.google.com/o/oauth2/v2/auth?" + string.Join("&", fields.Select(p => Uri.EscapeDataString(p.Key) + "=" + Uri.EscapeDataString(p.Value)));
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            using var peer = await listener.AcceptTcpClientAsync(ct);
            using var stream = peer.GetStream();
            // Limit the entire callback header before decoding it. Never log it.
            var header = new List<byte>(); var one = new byte[1];
            while (header.Count < 8192) {
                if (await stream.ReadAsync(one, ct) == 0) throw new InvalidDataException("授权回调中断。");
                header.Add(one[0]);
                if (header.Count >= 4 && header.TakeLast(4).SequenceEqual(new byte[] {13,10,13,10})) break;
            }
            if (header.Count >= 8192) throw new InvalidDataException("授权回调过长。");
            var first = Encoding.ASCII.GetString(header.ToArray()).Split("\r\n")[0].Split(' ');
            if (first.Length != 3 || first[0] != "GET" || first[2] != "HTTP/1.1") throw new InvalidDataException("授权回调格式错误。");
            var code = CallbackCode(first[1], state);
            const string message = "Authorization received. Return to the app.";
            var reply = Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {message.Length}\r\nConnection: close\r\n\r\n{message}");
            await stream.WriteAsync(reply, ct);
            await Exchange(new Dictionary<string,string> { ["grant_type"]="authorization_code", ["code"]=code, ["redirect_uri"]=redirect, ["code_verifier"]=verifier }, ct);
        } finally { listener.Stop(); }
    }
    private async Task Exchange(Dictionary<string,string> fields, CancellationToken ct) {
        fields["client_id"] = config.ClientId;
        if (config.ClientSecret.Length > 0) fields["client_secret"] = config.ClientSecret;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(65));
        using var response = await transport.Send(new Uri("https://oauth2.googleapis.com/token"), null, timeout.Token, new FormUrlEncodedContent(fields));
        byte[] body=await BoundedBody.Read(response.Content,timeout.Token);
        if (!response.IsSuccessStatusCode){
            using var failure=JsonDocument.Parse(body);
            if(failure.RootElement.TryGetProperty("error",out var code)&&code.GetString()=="invalid_grant")throw new ClientFailure("Google 要求重新授权（invalid_grant）。请点击登录；测试状态应用的刷新凭据可能受 Google 有效期限制。");
            throw new ClientFailure($"Google 授权服务返回 HTTP {(int)response.StatusCode}，请检查网络后重试。");
        }
        using var json = JsonDocument.Parse(body);
        var root = json.RootElement;
        if (root.GetProperty("token_type").GetString() is not "Bearer") throw new InvalidDataException("授权令牌类型无效。");
        if (root.TryGetProperty("scope", out var scope) && !scope.GetString()!.Split(' ').Contains("https://www.googleapis.com/auth/drive.readonly")) throw new InvalidDataException("未获得只读云盘权限。");
        access = root.GetProperty("access_token").GetString() ?? throw new InvalidDataException();
        expiry = DateTimeOffset.UtcNow.AddSeconds(root.GetProperty("expires_in").GetInt32() - 60);
        if (root.TryGetProperty("refresh_token", out var r)) refresh = r.GetString();
        if(refresh!=null)ProfileChanged?.Invoke(new DesktopProfile(config,refresh));
    }
    public async Task<string> Token(CancellationToken ct) {
        if (access == null && refresh == null) throw new InvalidOperationException("请先登录。");
        if (access == null || DateTimeOffset.UtcNow >= expiry) {
            if (refresh == null) throw new InvalidOperationException("登录已过期，请重新登录。");
            await Exchange(new Dictionary<string,string> { ["grant_type"]="refresh_token", ["refresh_token"]=refresh }, ct);
        }
        return access!;
    }
    public void Forget() { access = refresh = null; expiry = default; }
}
internal static class BoundedBody
{
    public static async Task<byte[]> Read(HttpContent content, CancellationToken ct, int limit = 1024 * 1024) {
        using var stream = await content.ReadAsStreamAsync(ct);
        using var buffer = new MemoryStream(); var block = new byte[8192]; int n;
        while ((n = await stream.ReadAsync(block, ct)) > 0) { if (buffer.Length + n > limit) throw new InvalidDataException("服务响应超过安全上限。"); await buffer.WriteAsync(block.AsMemory(0,n), ct); }
        return buffer.ToArray();
    }
}
