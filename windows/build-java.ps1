param([string]$JavaHome=$env:JAVA_HOME,[switch]$Test)
$ErrorActionPreference='Stop'
if(!$JavaHome){$command=Get-Command javac -ErrorAction SilentlyContinue;if($command){$JavaHome=Split-Path (Split-Path $command.Source)}}
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))){throw 'Specify -JavaHome for an existing JDK 17 or newer.'}
Push-Location (Split-Path $PSScriptRoot)
try{
  $manifest=Get-Content windows/dependencies.json -Raw | ConvertFrom-Json
  foreach($entry in $manifest){$file=Join-Path windows/lib $entry.file;if(!(Test-Path -LiteralPath $file)){throw "Missing dependency $($entry.file); run restore-java.ps1 first"};if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256){throw "Dependency hash mismatch: $($entry.file)"}}
  New-Item -ItemType Directory -Force windows/build/main,windows/build/test | Out-Null
  $sources=@(Get-ChildItem app/src/main/java/dev/gdirect/lite/network,app/src/main/java/dev/gdirect/lite/drive,app/src/main/java/dev/gdirect/lite/transfer,app/src/main/java/dev/gdirect/lite/preview -Filter '*.java' -Recurse | ForEach-Object FullName)
  $sources+=@((Resolve-Path app/src/main/java/dev/gdirect/lite/diagnostics/DiagnosticLog.java).Path)
  $originalSources=@($sources)
  $sources+=@(Get-ChildItem windows/java -Filter '*.java' -Recurse | ForEach-Object FullName)
  & "$JavaHome/bin/javac.exe" --release 17 -encoding UTF-8 -cp 'windows/lib/*' -d windows/build/main @sources
  if($LASTEXITCODE){throw 'Java core compilation failed'}
  Copy-Item -LiteralPath app/src/main/resources/gts-roots.pem,app/src/main/resources/gts-roots.LICENSE,app/src/main/resources/google-bootstrap.json -Destination windows/build/main
  $sourceMap=@($originalSources|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath((Get-Location).Path,$_);sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}})
  [ordered]@{repository='qingzhuernest/routeshelf-java';upstreamCommit='20e872b8b2935521003c98c85a2278a9ed6817a7';compiledSharedSources=$sourceMap}|ConvertTo-Json -Depth 5|Set-Content windows/build/SOURCE-IDENTITY.json -Encoding utf8
  if($Test){
    $tests=@(Get-ChildItem app/src/test/java/dev/gdirect/lite/network,app/src/test/java/dev/gdirect/lite/drive,app/src/test/java/dev/gdirect/lite/transfer,app/src/test/java/dev/gdirect/lite/preview,windows/java-test -Filter '*Test.java' -Recurse | ForEach-Object FullName)
    $tests+=@((Resolve-Path app/src/test/java/dev/gdirect/lite/diagnostics/DiagnosticLogTest.java).Path)
    & "$JavaHome/bin/javac.exe" --release 17 -encoding UTF-8 -cp 'windows/lib/*;windows/build/main' -d windows/build/test @tests
    if($LASTEXITCODE){throw 'Java tests compilation failed'}
    $names=@($tests|ForEach-Object{$source=Get-Content -LiteralPath $_ -Raw;[regex]::Match($source,'package\s+([\w.]+);').Groups[1].Value+'.'+[IO.Path]::GetFileNameWithoutExtension($_)})
    & "$JavaHome/bin/java.exe" -cp 'windows/lib/*;windows/build/main;windows/build/test' org.junit.runner.JUnitCore @names
    if($LASTEXITCODE){throw 'Java core tests failed'}
  }
}finally{Pop-Location}
