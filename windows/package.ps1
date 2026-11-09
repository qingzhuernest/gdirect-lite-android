param([Parameter(Mandatory)][string]$JavaHome,[string]$Framework='net10.0')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/check.ps1" -JavaHome $JavaHome -Framework $Framework
Push-Location (Split-Path $PSScriptRoot)
try{
 $destination=Join-Path $PSScriptRoot 'dist/portable'
 if(Test-Path -LiteralPath $destination){throw 'Output exists; use a fresh output directory.'}
 dotnet publish windows/App/GDirect.Windows.csproj -c Release "-p:GDirectFramework=$Framework" -r win-x64 --self-contained true --source https://api.nuget.org/v3/index.json -o $destination
 if($LASTEXITCODE){throw 'Publish failed'}
 New-Item -ItemType Directory -Force "$destination/engine/classes" | Out-Null
 Get-ChildItem windows/build/main -Force|Copy-Item -Destination "$destination/engine/classes" -Recurse
 Get-ChildItem windows/lib -Filter '*.jar'|Where-Object{$_.Name -notmatch 'junit|hamcrest|mockwebserver|okhttp-tls'}|Copy-Item -Destination "$destination/engine"
 if(!(Test-Path -LiteralPath "$JavaHome/legal")){throw 'Runtime must retain its legal directory'}
 New-Item -ItemType Directory -Path "$destination/jre" | Out-Null
 foreach($item in @('bin','conf','lib','legal','release')){Copy-Item -LiteralPath (Join-Path $JavaHome $item) -Destination "$destination/jre" -Recurse}
 Copy-Item windows/README.md,windows/THIRD_PARTY_NOTICES.md,windows/build/SOURCE-IDENTITY.json,LICENSE -Destination $destination
 [ordered]@{version='0.2.0';upstream='0456bf7abb6e0bd204895ec4eb268dfcb8f5069a';signing='unsigned development preview';framework=$Framework;realChinaNetworkVerified=$false}|ConvertTo-Json|Set-Content "$destination/BUILD-IDENTITY.json" -Encoding utf8
 Get-ChildItem -LiteralPath $destination -File -Recurse|ForEach-Object{(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash+'  '+[IO.Path]::GetRelativePath($destination,$_.FullName)}|Set-Content "$destination/SHA256SUMS.txt"
 Compress-Archive -Path "$destination/*" -DestinationPath windows/dist/GDirectLite-Windows-0.2.0.zip
}finally{Pop-Location}
