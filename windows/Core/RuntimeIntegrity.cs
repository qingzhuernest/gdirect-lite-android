using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
namespace GDirect;
internal static class RuntimeIntegrity
{
    internal static string VerifiedClasspath(string directory){
        using var resource=typeof(RuntimeIntegrity).Assembly.GetManifestResourceStream("GDirect.Dependencies.json")??throw new ClientFailure("缺少组件校验信息，请重新解压完整程序。");
        using var json=JsonDocument.Parse(resource);var expected=new HashSet<string>(StringComparer.OrdinalIgnoreCase);var paths=new List<string>();
        foreach(var entry in json.RootElement.EnumerateArray()){
            string name=entry.GetProperty("file").GetString()!;
            if(name.StartsWith("junit-")||name.StartsWith("hamcrest-")||name.StartsWith("mockwebserver-")||name.StartsWith("okhttp-tls-"))continue;
            expected.Add(name);string path=Path.Combine(directory,"engine",name);
            if(!File.Exists(path))throw new ClientFailure("缺少运行组件，请重新解压完整程序。");
            using var input=File.OpenRead(path);string actual=Convert.ToHexString(SHA256.HashData(input));
            if(!actual.Equals(entry.GetProperty("sha256").GetString(),StringComparison.OrdinalIgnoreCase))throw new ClientFailure("运行组件校验失败，已停止启动。请使用完整的原始程序包。");
            paths.Add(path);
        }
        if(Directory.EnumerateFiles(Path.Combine(directory,"engine"),"*.jar").Any(path=>!expected.Contains(Path.GetFileName(path))))throw new ClientFailure("程序目录出现未知组件，已停止启动。请重新解压完整程序。");
        paths.Add(Path.Combine(directory,"engine","classes"));return string.Join(Path.PathSeparator,paths);
    }
}
