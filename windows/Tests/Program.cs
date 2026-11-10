using GDirect;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

int passed=0;
void Check(bool condition,string name){if(!condition)throw new Exception(name);passed++;Console.WriteLine("PASS "+name);}
try{
    var accountState=new AccountState();accountState.Authorized();
    Check(!accountState.HandleFailure(new HttpRequestException())&&accountState.Connected,"network failures preserve authorization");
    using(var reply=JsonDocument.Parse("{\"error\":\"login required\",\"errorCode\":\"AUTHORIZATION\"}"))
        Check(JavaBridge.RequestFailure(reply.RootElement) is AuthorizationFailure,"Java authorization failure preserves its type");
    using(var reply=JsonDocument.Parse("{\"error\":\"request failed\",\"errorCode\":\"REQUEST\"}"))
        Check(JavaBridge.RequestFailure(reply.RootElement) is not AuthorizationFailure,"ordinary Java failures do not require login");
    using(var transport=new GoogleTransport(Path.Combine(Path.GetTempPath(),"Resources"))){
        transport.TokenExchangeFixture=(form,ct)=>Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.BadRequest){Content=new StringContent("{\"error\":\"invalid_grant\"}")});
        var expired=OAuthSession.Restore(transport,new DesktopProfile(new DesktopConfig("fixture.apps.googleusercontent.com",""),"fixture-refresh"));
        try{await expired.Token(CancellationToken.None);throw new Exception("expired grant accepted");}
        catch(AuthorizationFailure error){Check(accountState.HandleFailure(error)&&!accountState.Connected,"invalid grant unlocks login state");}
        accountState.Authorized();Check(accountState.Connected,"re-login reconnects without an intermediate logout");
    }
    Check(await JavaBridge.ReadBoundedLine(new StringReader("first\nsecond\n"),5,CancellationToken.None)=="first","bounded pipe line accepted at exact limit");
    try{await JavaBridge.ReadBoundedLine(new StringReader("123456\n"),5,CancellationToken.None);throw new Exception("oversized pipe line accepted");}catch(ClientFailure){Check(true,"oversized pipe rejected during reading");}
    try{await JavaBridge.ReadBoundedLine(new StringReader("partial"),10,CancellationToken.None);throw new Exception("partial pipe accepted");}catch(ClientFailure){Check(true,"partial pipe rejected");}
    Check(OAuthSession.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")=="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM","PKCE reference vector");
    Check(OAuthSession.CallbackCode("/?state=fixture&code=code","fixture")=="code","callback accepted");
    try{OAuthSession.CallbackCode("/?state=bad&code=code","fixture");throw new Exception("state mismatch");}catch(ClientFailure){Check(true,"state mismatch rejected");}
    try{OAuthSession.CallbackCode("/?state=fixture&error=access_denied&error_description=private-data","fixture");throw new Exception("authorization denial accepted");}
    catch(ClientFailure error){Check(FailureText.Describe(error)=="Google 未授予访问权限，请重试登录。","denied authorization has a safe retry message");}
    string directory=Path.Combine(Path.GetTempPath(),"gdirect-profile-test-"+Guid.NewGuid().ToString("N"));
    try{
        Directory.CreateDirectory(directory);
        string configPath=Path.Combine(directory,"oauth.json");
        File.WriteAllText(configPath,"{\"web\":{}}");
        try{DesktopConfig.Load(configPath);throw new Exception("Web OAuth config accepted");}
        catch(ClientFailure error){Check(FailureText.Describe(error).Contains("桌面应用 OAuth JSON"),"wrong OAuth config has a safe recovery message");}
        var store=new WindowsProfileStore(directory);
        Check(store.Load()==null,"fresh profile has no credentials");
        var profile=new DesktopProfile(new DesktopConfig("fixture.apps.googleusercontent.com","fixture-client-secret"),"fixture-refresh-token");
        if(OperatingSystem.IsWindows()){store.Save(profile);Check(store.Load()==profile,"DPAPI profile survives new read");}
        using(var transport=new GoogleTransport(Path.Combine(directory,"Resources"))){
            int exchanges=0;
            transport.TokenExchangeFixture=async(form,ct)=>{
                exchanges++;string fields=await form.ReadAsStringAsync(ct);
                Check(fields.Contains("grant_type=refresh_token")&&fields.Contains("refresh_token=fixture-refresh-token"),"restart uses refresh grant without browser authorization code");
                return new HttpResponseMessage(System.Net.HttpStatusCode.OK){Content=new StringContent("{\"access_token\":\"fixture-access-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")};
            };
            var restored=OAuthSession.Restore(transport,profile);DesktopProfile? renewed=null;
            restored.ProfileChanged=value=>{renewed=value;if(OperatingSystem.IsWindows())store.Save(value);};
            Check(await restored.Token(CancellationToken.None)=="fixture-access-token","encrypted restart restores usable access token");
            Check(renewed?.RefreshToken==profile.RefreshToken,"refresh response without replacement keeps original refresh token");
            await restored.Token(CancellationToken.None);Check(exchanges==1,"unexpired access token reused without login");
        }
        if(OperatingSystem.IsWindows()){
        var encrypted=File.ReadAllBytes(Path.Combine(directory,"account.dpapi"));
        Check(!Encoding.UTF8.GetString(encrypted).Contains("fixture-refresh-token"),"refresh token not plaintext on disk");
        encrypted[^1]^=1;File.WriteAllBytes(Path.Combine(directory,"account.dpapi"),encrypted);
        try{store.Load();throw new Exception("tampered profile accepted");}catch(CryptographicException){Check(true,"tampered ciphertext rejected");}
        store.Save(profile);store.Forget();Check(store.Load()==null,"disconnect removes profile");
        }else Console.WriteLine("SKIP Windows-only DPAPI round-trip/tamper/delete checks on this platform");
    }finally{if(Directory.Exists(directory))Directory.Delete(directory,true);}
    Check(!FailureText.Describe(new HttpRequestException("token-secret")).Contains("token-secret"),"network errors are sanitized");
    if(args.Length==1){
        Check(!RuntimeIntegrity.VerifiedClasspath(args[0]).Contains('*'),"verified explicit classpath contains no wildcard");
        string fixture=Path.Combine(Path.GetTempPath(),"gdirect-integrity-"+Guid.NewGuid().ToString("N"));
        try{
            Directory.CreateDirectory(Path.Combine(fixture,"engine"));
            var libraries=Directory.GetFiles(Path.Combine(args[0],"engine"),"*.jar");
            foreach(var library in libraries)File.Copy(library,Path.Combine(fixture,"engine",Path.GetFileName(library)));
            string first=Path.Combine(fixture,"engine",Path.GetFileName(libraries[0]));
            File.AppendAllText(first,"tampered fixture");
            try{RuntimeIntegrity.VerifiedClasspath(fixture);throw new Exception("modified library accepted");}catch(ClientFailure){Check(true,"modified library rejected before child launch");}
            File.Copy(libraries[0],first,true);File.WriteAllText(Path.Combine(fixture,"engine","unknown.jar"),"fixture");
            try{RuntimeIntegrity.VerifiedClasspath(fixture);throw new Exception("unknown library accepted");}catch(ClientFailure){Check(true,"unexpected library rejected before child launch");}
        }finally{if(Directory.Exists(fixture))Directory.Delete(fixture,true);}
        using var engine=new JavaBridge(args[0]);
        using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try{await engine.Invoke(new{op="invalid-fixture"},timeout.Token);throw new Exception("invalid op accepted");}catch(ClientFailure){Check(true,"actual JVM pipe error framing");}
        try{await engine.Invoke(new{op="public",link="https://example.com/private",destination=Path.Combine(Path.GetTempPath(),"must-not-write")},timeout.Token);throw new Exception("evil public origin accepted");}catch(ClientFailure){Check(true,"public origin rejected via actual JVM without network");}
    }
    Console.WriteLine($"{passed} Windows adapter checks passed.");
}catch(Exception error){Console.Error.WriteLine(error);Environment.ExitCode=1;}
