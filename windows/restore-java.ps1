$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try{
  New-Item -ItemType Directory -Force lib | Out-Null
  $coordinates=@('com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0','com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0','org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10','org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.8.21/kotlin-stdlib-jdk8-1.8.21','org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.8.21/kotlin-stdlib-jdk7-1.8.21','org/jetbrains/annotations/13.0/annotations-13.0','com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1','junit/junit/4.13.2/junit-4.13.2','org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3','com/squareup/okhttp3/mockwebserver/4.12.0/mockwebserver-4.12.0','com/squareup/okhttp3/okhttp-tls/4.12.0/okhttp-tls-4.12.0')
  $manifest=Get-Content dependencies.json -Raw|ConvertFrom-Json
  foreach($coordinate in $coordinates){
    $name=$coordinate.Split('/')[-1]+'.jar';$path=Join-Path lib $name
    $expected=($manifest|Where-Object file -eq $name).sha256
    if(!$expected){throw "Unpinned dependency $name"}
    if(!(Test-Path -LiteralPath $path)){Invoke-WebRequest -Uri ('https://repo.maven.apache.org/maven2/'+$coordinate+'.jar') -OutFile $path}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected){throw "Dependency checksum failed: $name"}
  }
}finally{Pop-Location}
