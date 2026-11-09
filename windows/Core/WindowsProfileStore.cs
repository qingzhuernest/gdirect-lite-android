using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;

namespace GDirect;
public sealed record DesktopProfile(DesktopConfig Configuration,string RefreshToken);

// Windows DPAPI, current user only. Neither access tokens nor plaintext JSON are persisted.
public sealed class WindowsProfileStore
{
    private readonly string path;
    public WindowsProfileStore(string? directory=null){path=Path.Combine(directory??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"GDirectLite","Windows"),"account.dpapi");}
    [StructLayout(LayoutKind.Sequential)] private struct Blob {public int Size;public IntPtr Data;}
    [DllImport("crypt32.dll",SetLastError=true,CharSet=CharSet.Unicode)] [return:MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(ref Blob input,string? description,IntPtr entropy,IntPtr reserved,IntPtr prompt,int flags,out Blob output);
    [DllImport("crypt32.dll",SetLastError=true,CharSet=CharSet.Unicode)] [return:MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(ref Blob input,IntPtr description,IntPtr entropy,IntPtr reserved,IntPtr prompt,int flags,out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);
    internal static byte[] Protect(byte[] source,bool decrypt=false){
        var input=new Blob{Size=source.Length,Data=Marshal.AllocHGlobal(source.Length)};Blob output=default;
        try{
            Marshal.Copy(source,0,input.Data,source.Length);
            bool success=decrypt?CryptUnprotectData(ref input,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,1,out output):CryptProtectData(ref input,null,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,1,out output);
            if(!success)throw new CryptographicException(new Win32Exception(Marshal.GetLastWin32Error()).Message);
            if(output.Size<0||output.Size>131072)throw new CryptographicException("Credential size rejected");
            var result=new byte[output.Size];Marshal.Copy(output.Data,result,0,result.Length);return result;
        }finally{
            for(int i=0;i<input.Size;i++)Marshal.WriteByte(input.Data,i,0);Marshal.FreeHGlobal(input.Data);
            if(output.Data!=IntPtr.Zero){for(int i=0;i<output.Size;i++)Marshal.WriteByte(output.Data,i,0);LocalFree(output.Data);}
        }
    }
    public void Save(DesktopProfile profile){
        if(profile.RefreshToken.Length is 0 or >16384)throw new InvalidDataException("Refresh token missing");
        byte[] clear=JsonSerializer.SerializeToUtf8Bytes(profile);
        byte[] encrypted;try{encrypted=Protect(clear);}finally{CryptographicOperations.ZeroMemory(clear);}
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);var temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{File.WriteAllBytes(temp,encrypted);File.Move(temp,path,true);}finally{if(File.Exists(temp))File.Delete(temp);}
    }
    public DesktopProfile? Load(){
        if(!File.Exists(path))return null;
        if(new FileInfo(path).Length>131072)throw new InvalidDataException("Credential size rejected");
        byte[] clear=Protect(File.ReadAllBytes(path),true);
        try{return JsonSerializer.Deserialize<DesktopProfile>(clear)??throw new InvalidDataException("Credential format rejected");}finally{CryptographicOperations.ZeroMemory(clear);}
    }
    public void Forget(){if(File.Exists(path))File.Delete(path);}
}
