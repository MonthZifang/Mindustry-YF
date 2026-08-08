@echo off
rem ============================================================
rem  netwatch 核心网络模块构建脚本 (Go) + 热重载
rem
rem  用法: build.bat
rem  流程: go build 到临时文件 -> 通知网关停止模块解锁 exe -> 替换
rem        -> 文件监听器自动检测变更并热重启模块
rem
rem  前置: 已安装 Go (go version 可运行), 网关已启用且 netmods.hotReload=true。
rem ============================================================
setlocal

rem 定位到脚本所在目录 (兼容中文路径与任意调用位置)
cd /d "%~dp0"

set OUT=netwatch.exe
set TMP_OUT=netwatch.build.tmp.exe

set GATEWAY_URL=http://localhost:7100
set MODULE_ID=netwatch

where go >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 go, 请先安装 Go 工具链。
    exit /b 1
)

echo 正在编译 main.go ...
go build -o %TMP_OUT% .
if errorlevel 1 (
    echo [错误] 编译失败。
    if exist %TMP_OUT% del /q %TMP_OUT% >nul 2>nul
    exit /b 1
)
echo 编译成功: %TMP_OUT%

echo 通知网关停止运行中的模块以解锁文件...
where curl >nul 2>nul
if not errorlevel 1 (
    curl -s -X POST "%GATEWAY_URL%/yzfnet/netmods/stop" -H "Content-Type: application/json" -d "{\"id\":\"%MODULE_ID%\"}" >nul 2>nul
    timeout /t 2 /nobreak >nul 2>nul
)

if exist %OUT% del /q %OUT% >nul 2>nul
move /Y %TMP_OUT% %OUT% >nul 2>nul
if errorlevel 1 (
    echo [警告] 替换 %OUT% 失败, 模块可能仍在运行。请确认网关已停止该模块后重试。
    exit /b 1
)
echo 已更新: %OUT%
echo.
echo 文件监听器将自动检测到变更并热重启模块 (无需重启服务端)。
endlocal
