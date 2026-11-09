using System.Text.Json;
namespace GDirect;
public sealed record DriveFile(string Id,string Name,string MimeType,long? Size,string? Md5,string Modified,bool CanDownload)
{
    public bool IsFolder=>MimeType=="application/vnd.google-apps.folder";
    public string Kind=>IsFolder?"文件夹":MimeType.StartsWith("application/vnd.google-apps.",StringComparison.Ordinal)?"Google 文档":"文件";
    public string SizeText=>Size.HasValue?$"{Size:N0} B":"—";
}
public sealed record DrivePage(IReadOnlyList<DriveFile> Files,string? Next);
// Presentation mapping only. Original DriveApi builds URLs, parses pages, resolves
// shortcuts and resource keys; original DriveTransport owns credential/redirect rules.
public sealed class DriveClient
{
    private readonly GoogleTransport transport;private readonly OAuthSession session;
    public DriveClient(GoogleTransport transport,OAuthSession session){this.transport=transport;this.session=session;}
    public async Task<DrivePage> List(string folder,string search,string? page,CancellationToken ct){
        var result=await transport.Engine.Invoke(new{op="list",folder,search,page=page??"",token=await session.Token(ct)},ct);
        return result.Deserialize<DrivePage>()??throw new ClientFailure("核心返回的目录无效。");
    }
    public static (string Mime,string Extension)? Export(DriveFile file)=>file.MimeType switch {
        "application/vnd.google-apps.spreadsheet"=>("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",".xlsx"),
        "application/vnd.google-apps.document" or "application/vnd.google-apps.presentation" or "application/vnd.google-apps.drawing"=>("application/pdf",".pdf"),_=>null
    };
    public async Task Download(DriveFile file,string destination,IProgress<long> progress,CancellationToken ct){
        var temporary=Path.GetFullPath(destination)+"."+Guid.NewGuid().ToString("N")+".part";
        try{await transport.Engine.Invoke(new{op="download",id=file.Id,destination,temporary,token=await session.Token(ct)},ct,progress);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
}
