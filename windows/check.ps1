param([string]$JavaHome=$env:JAVA_HOME,[string]$Framework='net10.0')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/build-java.ps1" -JavaHome $JavaHome -Test
Push-Location $PSScriptRoot
try{
 dotnet restore Tests/GDirect.Tests.csproj "-p:GDirectFramework=$Framework" --configfile NuGet.Config
 if($LASTEXITCODE){throw 'Restore failed'}
 dotnet build Tests/GDirect.Tests.csproj "-p:GDirectFramework=$Framework" --no-restore
 if($LASTEXITCODE){throw 'Adapter test build failed'}
 dotnet "Tests/bin/Debug/$Framework/GDirect.Tests.dll"
 if($LASTEXITCODE){throw 'Windows adapter tests failed'}
 dotnet restore App/GDirect.Windows.csproj "-p:GDirectFramework=$Framework" --configfile NuGet.Config
 if($LASTEXITCODE){throw 'Restore failed'}
 dotnet build App/GDirect.Windows.csproj "-p:GDirectFramework=$Framework" --no-restore
 if($LASTEXITCODE){throw 'WPF build failed'}
}finally{Pop-Location}
