using System.Collections.ObjectModel;
using System.IO;
using System.Net.Http;
using System.Windows;
using System.Windows.Input;
using Microsoft.Win32;

namespace GDirect.Windows;
public partial class MainWindow : Window
{
    private readonly GoogleTransport transport;
    private DesktopConfig? config;
    private OAuthSession? session;
    private DriveClient? drive;
    private CancellationTokenSource? operation;
    private readonly ObservableCollection<DriveFile> items = new();
    private readonly List<(string Id, string Name)> path = new() { ("root", "我的云盘") };
    private string query = "";
    private string? next;
    private bool connected;
    private bool listingLoaded;
    private readonly WindowsProfileStore profileStore=new();
    private bool credentialSaved;
    private bool hasRememberedProfile;
    public MainWindow() {
        InitializeComponent();
        MaxHeight=SystemParameters.WorkArea.Height;
        Height=Math.Min(680,MaxHeight-12);
        transport = new GoogleTransport(Path.Combine(AppContext.BaseDirectory, "Resources"));
        Files.ItemsSource = items;
        Closed += (_, _) => { operation?.Cancel(); session?.Forget(); if (operation == null) transport.Dispose(); };
        SearchText.SetValue(System.Windows.Automation.AutomationProperties.NameProperty, "按名称搜索整个云盘");
        Update();
        Loaded+=async (_,_)=>await RestoreProfile();
    }
    private void Remember(OAuthSession value){value.ProfileChanged=profile=>{
        try{profileStore.Save(profile);credentialSaved=true;hasRememberedProfile=true;}
        catch(Exception e) when(e is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException){credentialSaved=false;}
    };}
    private string AccountSummary()=>credentialSaved?"已授权 · 只读访问 · 登录凭据已由 Windows 加密保存":"已授权 · 只读访问 · 本次凭据未能保存，下次需重新登录";
    private async Task RestoreProfile(){
        DesktopProfile? saved;
        try{saved=profileStore.Load();}
        catch(Exception e) when(e is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException){StatusText.Text="无法读取 Windows 加密登录状态，请选择原配置并重新登录。";return;}
        if(saved==null)return;
        hasRememberedProfile=true;
        config=saved.Configuration;
        await Run("正在通过原项目直连核心恢复登录，无需打开浏览器…",async ct=>{
            var restored=OAuthSession.Restore(transport,saved);Remember(restored);
            await restored.Token(ct);session=restored;drive=new DriveClient(transport,session);connected=true;
            AccountText.Text=AccountSummary();await Load(false,ct);
        });
    }
    private void Update() {
        var idle = operation == null;
        ConfigButton.IsEnabled = LoginButton.IsEnabled = idle && !connected;
        CheckButton.IsEnabled = idle;
        LoginButton.IsEnabled &= config != null;
        LogoutButton.IsEnabled = (connected||hasRememberedProfile) && idle;
        HomeButton.IsEnabled = SearchButton.IsEnabled = SearchText.IsEnabled = RefreshButton.IsEnabled = connected && idle;
        BackButton.IsEnabled = connected && idle && (path.Count > 1 || query.Length > 0);
        MoreButton.IsEnabled = connected && idle && next != null;
        DownloadButton.IsEnabled = connected && idle && Files.SelectedItem is DriveFile { IsFolder:false, CanDownload:true };
        Files.IsEnabled = idle;
        PublicButton.IsEnabled=LinkText.IsEnabled=idle;
        CancelButton.IsEnabled = !idle;
        Progress.Visibility = idle ? Visibility.Collapsed : Visibility.Visible;
        Progress.IsIndeterminate = !idle;
        EmptyText.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyText.Text = !connected ? "登录后浏览文件夹和下载文件" : operation != null ? "正在读取，请稍候…" : listingLoaded ? "此位置没有可显示的文件" : "文件列表尚未读取成功，请点击刷新";
        PathText.Text = query.Length > 0 ? "全盘搜索：" + query : string.Join(" / ", path.Select(x => x.Name));
    }
    private async Task Run(string message, Func<CancellationToken, Task> action) {
        if (operation != null) return;
        operation = new CancellationTokenSource(); StatusText.Text = message; Update();
        try { await action(operation.Token); }
        catch (OperationCanceledException) { StatusText.Text = "操作已取消或超时，可重试。"; }
        catch (Exception e) when (e is HttpRequestException or IOException or InvalidOperationException or KeyNotFoundException or ArgumentException or System.Text.Json.JsonException or System.Security.Cryptography.CryptographicException or System.ComponentModel.Win32Exception) {
            // Never surface raw network/JSON exception text containing private data.
            StatusText.Text = FailureText.Describe(e);
        }
        finally { operation.Dispose(); operation = null; Update(); }
    }
    private void Configure(object sender, RoutedEventArgs e) {
        var picker = new OpenFileDialog { Filter="Google 桌面授权配置 (*.json)|*.json" };
        if (picker.ShowDialog() != true) return;
        try { config = DesktopConfig.Load(picker.FileName); AccountText.Text = "桌面授权配置已载入"; StatusText.Text="点击登录；此配置与 Android 客户端独立。"; }
        catch (Exception ex) when (ex is IOException or System.Text.Json.JsonException or InvalidOperationException or KeyNotFoundException) { StatusText.Text="无法读取配置。请选择 Google 桌面应用的 OAuth JSON。"; }
        Update();
    }
    private async void Login(object sender, RoutedEventArgs e) => await Run("请在浏览器中授权，最长等待 3 分钟…", async ct => {
        var attempt = new OAuthSession(transport, config!);
        Remember(attempt);
        await attempt.Login(ct); session = attempt; drive = new DriveClient(transport, session); connected = true;
        AccountText.Text = AccountSummary();
        await Load(false, ct);
    });
    private void Logout(object sender, RoutedEventArgs e) {
        try{profileStore.Forget();}catch(Exception error)when(error is IOException or UnauthorizedAccessException){StatusText.Text="无法删除加密登录状态，请重试断开。";return;}
        credentialSaved=hasRememberedProfile=false;
        session?.Forget(); session = null; drive = null; connected = false; items.Clear(); path.RemoveRange(1,path.Count-1); query=""; next=null;
        AccountText.Text="已断开"; StatusText.Text="本地会话已清除。可在 Google 账户中撤销应用授权。"; Update();
    }
    private async void Check(object sender, RoutedEventArgs e) => await Run("正在验证数据线路，最长约 55 秒…", async ct => { await transport.Check(ct); StatusText.Text="云盘 API 无凭据检测通过。账户授权和实际下载仍需分别验证。"; });
    private async Task Load(bool append, CancellationToken ct) {
        listingLoaded=false;
        var page = await drive!.List(path[^1].Id, query, append ? next : null, ct);
        if (!append) items.Clear();
        foreach (var file in page.Files) if (!items.Any(x => x.Id == file.Id)) items.Add(file);
        listingLoaded=true;
        next=page.Next; StatusText.Text=$"已显示 {items.Count} 个项目" + (next != null ? "，可加载更多。" : "。");
    }
    private async void Refresh(object sender, RoutedEventArgs e) => await Run("正在刷新…", ct => Load(false, ct));
    private async void More(object sender, RoutedEventArgs e) => await Run("正在加载下一页…", ct => Load(true, ct));
    private async void Search(object sender, RoutedEventArgs e) {
        if (operation != null || !connected) return;
        query=SearchText.Text.Trim(); next=null; items.Clear(); await Run("正在搜索…", ct => Load(false, ct));
    }
    private void SearchKey(object sender, KeyEventArgs e) { if (e.Key == Key.Enter) Search(sender,e); }
    private async void OpenFolder(object sender, MouseButtonEventArgs e) {
        if (operation != null || Files.SelectedItem is not DriveFile { IsFolder:true } file) return;
        if (query.Length > 0) path.RemoveRange(1,path.Count-1);
        path.Add((file.Id,file.Name)); query=""; SearchText.Clear(); next=null; items.Clear(); await Run("正在打开文件夹…", ct => Load(false,ct));
    }
    private void FilesKey(object sender,KeyEventArgs e){if(e.Key==Key.Enter&&Files.SelectedItem is DriveFile {IsFolder:true}){e.Handled=true;OpenFolder(sender,null!);}}
    private async void Back(object sender, RoutedEventArgs e) {
        if (query.Length > 0) { query=""; SearchText.Clear(); } else if (path.Count > 1) path.RemoveAt(path.Count-1);
        items.Clear(); next=null; await Run("正在返回…", ct => Load(false,ct));
    }
    private async void Home(object sender, RoutedEventArgs e) { path.RemoveRange(1,path.Count-1); query=""; SearchText.Clear(); items.Clear(); next=null; await Run("正在打开我的云盘…", ct => Load(false,ct)); }
    private void SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e) => Update();
    private void Cancel(object sender, RoutedEventArgs e) => operation?.Cancel();
    private async void PublicDownload(object sender,RoutedEventArgs e){
        var link=LinkText.Text.Trim();
        if(link.Length==0){StatusText.Text="请粘贴公开 Google Drive 单文件分享链接。";return;}
        string? name=null;
        await Run("正在通过原项目核心检查公开链接…",async ct=>{name=await transport.PublicFilename(link,ct);});
        if(name==null)return;
        name=string.Concat(name.Select(c=>Path.GetInvalidFileNameChars().Contains(c)?'_':c)).TrimEnd('.',' ');
        var picker=new SaveFileDialog{FileName=name.Length==0?"GoogleDrive-download":name,Title="保存公开分享文件",OverwritePrompt=true};
        if(picker.ShowDialog()!=true)return;
        if(File.Exists(picker.FileName)){StatusText.Text="请选择尚不存在的文件名。";return;}
        await Run("正在通过原项目核心检查公开链接并下载…",async ct=>{
            await transport.DownloadPublic(link,picker.FileName,new Progress<long>(bytes=>StatusText.Text=$"公开文件下载中：{bytes/1048576.0:N1} MiB"),ct);
            StatusText.Text="公开链接下载完成，已检查可用的文件长度。";
        });
    }
    private async void Download(object sender, RoutedEventArgs e) {
        if (Files.SelectedItem is not DriveFile file) return;
        var extension = DriveClient.Export(file)?.Extension ?? "";
        var safe = string.Concat(file.Name.Select(c => Path.GetInvalidFileNameChars().Contains(c) ? '_' : c)).TrimEnd('.',' ');
        var picker = new SaveFileDialog { FileName=(safe.Length == 0 ? "download" : safe) + extension, OverwritePrompt=true };
        if (picker.ShowDialog() != true) return;
        if (File.Exists(picker.FileName)) { StatusText.Text="为保留已有文件，请选择一个新文件名。"; return; }
        await Run("正在准备下载…", async ct => {
            var progress = new Progress<long>(bytes => { StatusText.Text=$"正在下载：{bytes / 1048576.0:N1} MiB"; if (file.Size is > 0) { Progress.IsIndeterminate=false; Progress.Maximum=file.Size.Value; Progress.Value=bytes; } });
            await drive!.Download(file,picker.FileName,progress,ct); StatusText.Text="下载完成，校验通过，已保存到所选位置。";
        });
    }
}
