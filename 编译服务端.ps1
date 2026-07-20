$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

function Show-Text([string]$value){
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
}

Set-Location $PSScriptRoot

Write-Host ''
Write-Host '========================================'
Write-Host ('      ' + (Show-Text '5byA5aeL57yW6K+RIE1pbmR1c3RyeSDmnI3liqHnq68='))
Write-Host '========================================'
Write-Host (Show-Text '5LuF5p6E5bu65pyN5Yqh56uv5Y+R6KGM5YyF77yM5LiN5p6E5bu65a6i5oi356uv44CC')
Write-Host ''

& .\gradlew.bat :server:dist --no-daemon -PnoLocalArc=true
if($LASTEXITCODE -ne 0){
    Write-Host ''
    Write-Host (Show-Text 'W+Wksei0pV0g5pyN5Yqh56uv57yW6K+R5aSx6LSl77yM6K+35p+l55yL5LiK5pa56ZSZ6K+v5L+h5oGv44CC') -ForegroundColor Red
    exit $LASTEXITCODE
}

$artifact = Join-Path $PSScriptRoot 'server\build\libs\server-release.jar'
if(-not (Test-Path -LiteralPath $artifact -PathType Leaf)){
    Write-Host ''
    Write-Host (Show-Text 'W+Wksei0pV0g57yW6K+R5a6M5oiQ5L2G5pyq5om+5Yiw5pyN5Yqh56uv5Y+R6KGM5YyF44CC') -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host (Show-Text 'W+WujOaIkF0g5pyN5Yqh56uv5Y+R6KGM5YyF5bey55Sf5oiQ77ya') -ForegroundColor Green
Write-Host 'server\build\libs\server-release.jar'
