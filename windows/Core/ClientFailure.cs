using System.Net.Sockets;
using System.Security.Authentication;
using System.Text.Json;

namespace GDirect;

// Only application-owned, fixed messages may be shown; never raw exception text.
public sealed class ClientFailure : IOException
{
    public ClientFailure(string message) : base(message) { }
}
public static class FailureText
{
    public static string Describe(Exception error) {
        if (error is ClientFailure) return error.Message;
        if (error is JsonException or KeyNotFoundException) return "服务响应格式不符，请刷新重试。";
        for (Exception? cause=error; cause!=null; cause=cause.InnerException) {
            if (cause is AuthenticationException) return "TLS 证书或加密连接验证失败，请检查网络后重试。";
            if (cause is SocketException) return "网络连接失败，请检查网络后重试。";
        }
        return error switch {
            HttpRequestException => "Google 数据连接失败，请刷新重试；授权成功不代表云盘线路可用。",
            InvalidOperationException => "服务返回的数据或当前会话状态不符，请刷新；仍失败时重新登录。",
            IOException => "读取服务响应或本地文件失败，请检查网络或保存位置后重试。",
            _ => "操作未完成，请检查授权配置和网络后重试。"
        };
    }
}
