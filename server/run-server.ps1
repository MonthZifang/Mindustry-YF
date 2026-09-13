param(
    [string]$Jar = "build/libs/server-release.jar"
)

$ErrorActionPreference = "Stop"
$serverDir = $PSScriptRoot
$sourceJar = [IO.Path]::GetFullPath((Join-Path $serverDir $Jar))
if(-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)){
    throw "Server JAR not found: $sourceJar"
}

$libsDir = Split-Path -Parent $sourceJar
$runtimeDir = Join-Path $libsDir ".runtime"
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$runtimeJar = Join-Path $runtimeDir ("server-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".jar")
Copy-Item -LiteralPath $sourceJar -Destination $runtimeJar

Push-Location $libsDir
try{
    & java --enable-native-access=ALL-UNNAMED -XX:+HeapDumpOnOutOfMemoryError -jar $runtimeJar
    exit $LASTEXITCODE
}finally{
    Pop-Location
    Remove-Item -LiteralPath $runtimeJar -Force -ErrorAction SilentlyContinue
}
