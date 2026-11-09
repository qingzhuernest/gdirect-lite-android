using System.Buffers.Binary;
using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace GDirect;

// Secrets cross anonymous standard-I/O pipes, never arguments, env or TCP.
internal sealed class JavaBridge : IDisposable
{
    private readonly SemaphoreSlim gate=new(1,1);
    private Process? worker;
    private readonly string directory;
    public JavaBridge(string directory){this.directory=directory;}
    private Process Start(){
        if(worker is {HasExited:false})return worker;
        worker?.Dispose();
        var start=new ProcessStartInfo(Path.Combine(directory,"jre","bin","java.exe")){
            UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,
            StandardOutputEncoding=new UTF8Encoding(false,true),StandardErrorEncoding=Encoding.UTF8,WorkingDirectory=directory
        };
        start.ArgumentList.Add("-Dfile.encoding=UTF-8");
        start.ArgumentList.Add("-cp");start.ArgumentList.Add(RuntimeIntegrity.VerifiedClasspath(directory));
        start.ArgumentList.Add("dev.gdirect.lite.windows.DesktopWorker");
        worker=Process.Start(start)??throw new ClientFailure("无法启动原项目 Java 核心。");
        worker.ErrorDataReceived+=(_,_)=>{};worker.BeginErrorReadLine();return worker;
    }
    internal async Task<JsonElement> Invoke(object request,CancellationToken ct,IProgress<long>? progress=null){
        await gate.WaitAsync(ct);
        try{
            var process=Start();
            using var cancel=ct.Register(()=>{try{process.Kill(true);}catch(InvalidOperationException){}catch(System.ComponentModel.Win32Exception){}});
            byte[] payload=JsonSerializer.SerializeToUtf8Bytes(request);
            if(payload.Length>1048576)throw new ClientFailure("请求数据超过上限。");
            byte[] prefix=new byte[4];BinaryPrimitives.WriteInt32BigEndian(prefix,payload.Length);
            try{await process.StandardInput.BaseStream.WriteAsync(prefix,ct);await process.StandardInput.BaseStream.WriteAsync(payload,ct);await process.StandardInput.BaseStream.FlushAsync(ct);}
            finally{System.Security.Cryptography.CryptographicOperations.ZeroMemory(payload);}
            while(true){
                var line=await ReadBoundedLine(process.StandardOutput,2*1024*1024,ct);
                if(line==null)throw new ClientFailure("原项目 Java 核心已退出，请刷新重试。");
                if(line.Length>2*1024*1024)throw new ClientFailure("核心响应超过安全上限。");
                using var json=JsonDocument.Parse(line);var root=json.RootElement;
                if(root.TryGetProperty("progress",out var bytes)){progress?.Report(bytes.GetInt64());continue;}
                if(root.TryGetProperty("error",out var error))throw new ClientFailure(error.GetString()??"原项目核心请求失败。");
                return root.Clone();
            }
        }catch(Exception) when(ct.IsCancellationRequested){
            if(worker!=null)try{await worker.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));}catch(TimeoutException){}
            throw new OperationCanceledException(ct);
        }
        catch(Exception error) when(error is IOException or JsonException){ResetWorker();throw;}
        finally{gate.Release();}
    }
    internal static async Task<string?> ReadBoundedLine(TextReader reader,int limit,CancellationToken ct){
        var text=new StringBuilder(Math.Min(limit,4096));var character=new char[1];
        while(true){
            int count=await reader.ReadAsync(character.AsMemory(),ct);
            if(count==0)return text.Length==0?null:throw new ClientFailure("核心响应未完整结束，请重试。");
            if(character[0]=='\n')return text.ToString();
            if(text.Length>=limit)throw new ClientFailure("核心响应超过安全上限。");
            text.Append(character[0]);
        }
    }
    private void ResetWorker(){if(worker!=null){try{if(!worker.HasExited)worker.Kill(true);}catch(InvalidOperationException){}worker.Dispose();worker=null;}}
    public void Dispose(){ResetWorker();gate.Dispose();}
}
