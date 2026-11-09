using System.Net;
using System.Text;
using System.Text.Json;
namespace GDirect;
// Windows adapter only: all actual connections execute in original Java GoogleHttp.
public sealed class GoogleTransport : IDisposable
{
    internal readonly JavaBridge Engine;
    internal Func<HttpContent,CancellationToken,Task<HttpResponseMessage>>? TokenExchangeFixture {get;set;}
    public GoogleTransport(string resourceDirectory){Engine=new JavaBridge(Path.GetDirectoryName(resourceDirectory)!);}
    public static void Validate(Uri uri){if(uri.Scheme!="https"||uri.Host!="oauth2.googleapis.com"||uri.Port!=443||uri.UserInfo.Length!=0||uri.Fragment.Length!=0||uri.AbsolutePath!="/token")throw new ArgumentException("OAuth endpoint rejected");}
    public async Task<HttpResponseMessage> Send(Uri uri,string? token,CancellationToken ct,HttpContent? form=null){
        Validate(uri);if(token!=null||form==null)throw new ArgumentException("OAuth request rejected");
        if(TokenExchangeFixture!=null)return await TokenExchangeFixture(form,ct);
        var result=await Engine.Invoke(new{op="oauth",form=await form.ReadAsStringAsync(ct)},ct);
        return new HttpResponseMessage((HttpStatusCode)result.GetProperty("status").GetInt32()){Content=new StringContent(result.GetProperty("body").GetString()!,Encoding.UTF8,"application/json")};
    }
    public async Task Check(CancellationToken ct){await Engine.Invoke(new{op="check"},ct);}
    public async Task<string> PublicFilename(string link,CancellationToken ct){var info=await Engine.Invoke(new{op="publicInfo",link},ct);return info.GetProperty("name").GetString()??"GoogleDrive-download";}
    public async Task DownloadPublic(string link,string destination,IProgress<long> progress,CancellationToken ct){
        var temporary=Path.GetFullPath(destination)+"."+Guid.NewGuid().ToString("N")+".part";
        try{await Engine.Invoke(new{op="public",link,destination,temporary},ct,progress);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
    public void Dispose()=>Engine.Dispose();
}
