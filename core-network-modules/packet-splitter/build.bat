@echo off
rem ============================================================
rem  packet-splitter 核心网络模块构建脚本 (MSVC x64) + 热重载
rem
rem  用法: build.bat            (在开发者命令提示符中, 或自动初始化 MSVC)
rem  流程: 编译到临时文件 -> 通过网关 HTTP 停止运行中的模块以解锁 exe
rem        -> 替换正式 exe -> 文件监听器自动检测到变更并热重启模块
rem
rem  前置: 网关已启用且 netmods.hotReload=true, 默认 HTTP 端口 7100。
rem  若网关未运行(或模块未运行), 会跳过停止步骤直接替换。
rem ============================================================
setlocal

rem 定位到脚本所在目录 (兼容中文路径与任意调用位置)
cd /d "%~dp0"

set SRC=src\main.cpp
set OUT=packet-splitter.exe
set TMP_OUT=packet-splitter.build.tmp.exe

set GATEWAY_URL=http://localhost:7100
set MODULE_ID=packet-splitter

rem ---------- 初始化 MSVC 环境 ----------
where cl.exe >nul 2>nul
if errorlevel 1 (
    set VCVARS="C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
    if exist %VCVARS% (
        call %VCVARS% >nul
    ) else (
        echo [错误] 未找到 cl.exe, 请安装 Visual Studio Build Tools 或先在开发者命令提示符中运行。
        exit /b 1
    )
)

rem ---------- 编译到临时文件 ----------
echo 正在编译 %SRC% ...
cl.exe /nologo /std:c++17 /O2 /EHsc /W3 /utf-8 %SRC% /Fe:%TMP_OUT%
if errorlevel 1 (
    echo [错误] 编译失败。
    if exist %TMP_OUT% del /q %TMP_OUT% >nul 2>nul
    exit /b 1
)
echo 编译成功: %TMP_OUT%

rem ---------- 停止运行中的模块以解锁 exe (尽力而为) ----------
echo 通知网关停止运行中的模块以解锁文件...
where curl >nul 2>nul
if not errorlevel 1 (
    curl -s -X POST "%GATEWAY_URL%/yzfnet/netmods/stop" -H "Content-Type: application/json" -d "{\"id\":\"%MODULE_ID%\"}" >nul 2>nul
    rem 给进程一点时间退出释放文件锁
    timeout /t 2 /nobreak >nul 2>nul
)

rem ---------- 替换正式 exe ----------
if exist %OUT% del /q %OUT% >nul 2>nul
move /Y %TMP_OUT% %OUT% >nul 2>nul
if errorlevel 1 (
    echo [警告] 替换 %OUT% 失败, 模块可能仍在运行。请确认网关已停止该模块后重试。
    exit /b 1
)
echo 已更新: %OUT%
echo.
echo 文件监听器将自动检测到变更并热重启模块 (无需重启服务端)。
echo 也可手动触发: curl -X POST %GATEWAY_URL%/yzfnet/netmods/restart -d {"id":"%MODULE_ID%"}
endlocal
