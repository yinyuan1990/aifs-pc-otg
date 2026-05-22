@echo off
chcp 65001 >nul
setlocal

echo ========================================
echo    Phoenix 应用打包工具 (GStreamer 版)
echo ========================================
echo.

set RELEASE_DIR=D:\javafx\Acard\aic\Aifs\release
set BUILD_DIR=D:\javafx\Acard\aic\Aifs\build\Desktop_Qt_6_10_1_MSVC2022_64bit-Release
set QT_DIR=D:\javafx\Acard\aic\qt\6.10.1\msvc2022_64\bin
set FFMPEG_BIN=C:\ffmpeg\bin
set VCPKG_BIN=D:\javafx\Acard\aic\vcpkg\installed\x64-windows\bin
set GST_ROOT=C:\Program Files\gstreamer\1.0\msvc_x86_64
set INNO_SETUP="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
set INSTALLER_SCRIPT=D:\javafx\Acard\aic\Aifs\installer.iss
set INSTALLER_OUTPUT=D:\javafx\Acard\aic\Aifs\installer_output
set VCREDIST_PATH=D:\javafx\Acard\aic\Aifs\redist\vc_redist.x64.exe
set VCREDIST_URL=https://aka.ms/vs/17/release/vc_redist.x64.exe

:: 检查 Release 版本是否存在
if not exist "%BUILD_DIR%\Phoenix.exe" (
    echo [错误] 未找到 Release 版本的 Phoenix.exe
    echo 请先在 Qt Creator 中切换到 Release 模式并编译！
    echo.
    pause
    exit /b 1
)

echo [0/8] 检查 VC++ 运行库...
if not exist "%VCREDIST_PATH%" (
    echo     未找到本地 VC++ 运行库，正在下载...
    if not exist "D:\javafx\Acard\aic\Aifs\redist" mkdir "D:\javafx\Acard\aic\Aifs\redist"
    powershell -Command "Invoke-WebRequest -Uri '%VCREDIST_URL%' -OutFile '%VCREDIST_PATH%'"
    if exist "%VCREDIST_PATH%" (
        echo     VC++ 运行库下载完成！
    ) else (
        echo     [警告] VC++ 运行库下载失败，请手动下载并放置到:
        echo     %VCREDIST_PATH%
    )
) else (
    echo     VC++ 运行库已存在
)

echo [1/8] 清理并创建发布目录...
if exist "%RELEASE_DIR%" rmdir /s /q "%RELEASE_DIR%"
mkdir "%RELEASE_DIR%"

echo [2/8] 复制主程序...
copy "%BUILD_DIR%\Phoenix.exe" "%RELEASE_DIR%\" >nul
copy "%BUILD_DIR%\zjc_worker.exe" "%RELEASE_DIR%\" >nul 2>&1
if exist "%RELEASE_DIR%\zjc_worker.exe" (
    echo     已复制 zjc_worker.exe（子进程）
) else (
    echo     [警告] zjc_worker.exe 未找到，请确认已编译
)
:: WinDivert：zjc_worker 嵌入限速或外置 winshaper 时，需与 exe 同目录（见 winshaper\third_party\README.txt）
set "WINDIVERT_DIR=D:\javafx\Acard\aic\Aifs\winshaper\third_party\WinDivert\x64"
if exist "%WINDIVERT_DIR%\WinDivert.dll" (
    copy "%WINDIVERT_DIR%\WinDivert.dll" "%RELEASE_DIR%\" >nul 2>&1
    if exist "%WINDIVERT_DIR%\WinDivert64.sys" copy "%WINDIVERT_DIR%\WinDivert64.sys" "%RELEASE_DIR%\" >nul 2>&1
    if exist "%WINDIVERT_DIR%\WinDivert.sys" copy "%WINDIVERT_DIR%\WinDivert.sys" "%RELEASE_DIR%\" >nul 2>&1
    copy "D:\javafx\Acard\aic\Aifs\winshaper\third_party\WinDivert\LICENSE" "%RELEASE_DIR%\WinDivert-LICENSE.txt" >nul 2>&1
    echo     已复制 WinDivert 运行时 + LICENSE（LGPL，供 TRAFFIC_SHAPE）
) else (
    echo     [提示] 未找到 WinDivert\x64，已跳过 — 若使用后台限速请自备 DLL/sys
)

echo [3/8] 部署 Qt 依赖（这可能需要一点时间）...
"%QT_DIR%\windeployqt.exe" --release --qmldir D:\javafx\Acard\aic\Aifs "%RELEASE_DIR%\Phoenix.exe" >nul 2>&1

echo [4/8] 复制 VC++ 运行库安装程序...
if exist "%VCREDIST_PATH%" (
    copy "%VCREDIST_PATH%" "%RELEASE_DIR%\" >nul 2>&1
    echo     已复制 vc_redist.x64.exe
) else (
    echo     [警告] VC++ 运行库不存在，跳过
)

echo [5/8] 复制 FFmpeg DLL（颜色调整备用）...
copy "%FFMPEG_BIN%\avformat-62.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%FFMPEG_BIN%\avcodec-62.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%FFMPEG_BIN%\avutil-60.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%FFMPEG_BIN%\swscale-9.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%FFMPEG_BIN%\swresample-6.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [6/8] 复制 turbojpeg DLL...
copy "%VCPKG_BIN%\turbojpeg.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [7/8] 复制 GStreamer 运行时...
:: GStreamer 核心 DLL
copy "%GST_ROOT%\bin\gstreamer-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstapp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstvideo-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstbase-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gobject-2.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\glib-2.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gmodule-2.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gio-2.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\intl-8.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\ffi-7.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\pcre2-8-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\z-1.dll" "%RELEASE_DIR%\" >nul 2>&1
:: GStreamer WebRTC 相关 DLL
copy "%GST_ROOT%\bin\gstsdp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstrtp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstwebrtc-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstwebrtcnice-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\nice-10.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\libcrypto-3-x64.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\libssl-3-x64.dll" "%RELEASE_DIR%\" >nul 2>&1
:: 额外依赖 DLL
copy "%GST_ROOT%\bin\orc-0.4-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstpbutils-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstaudio-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gsttag-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstnet-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstsctp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [8/8] 复制 GStreamer 插件（解码器 + JPEG 编码）...
:: 创建 GStreamer 运行时目录结构
mkdir "%RELEASE_DIR%\runtime\gstreamer\bin" >nul 2>&1
mkdir "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0" >nul 2>&1
mkdir "%RELEASE_DIR%\runtime\gstreamer\libexec\gstreamer-1.0" >nul 2>&1

:: 复制插件扫描器
copy "%GST_ROOT%\libexec\gstreamer-1.0\gst-plugin-scanner.exe" "%RELEASE_DIR%\runtime\gstreamer\libexec\gstreamer-1.0\" >nul 2>&1

:: 复制核心 DLL 到 runtime 目录（供插件使用）
xcopy "%GST_ROOT%\bin\*.dll" "%RELEASE_DIR%\runtime\gstreamer\bin\" /Q /Y >nul 2>&1

:: 复制必需的插件
copy "%GST_ROOT%\lib\gstreamer-1.0\gstcoreelements.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstapp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstvideoconvertscale.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstvideorate.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstvideoparsersbad.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstd3d11.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstjpeg.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstmultifile.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstautodetect.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gsttypefindfunctions.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstplayback.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstvideofilter.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: ⭐ 解码器插件（关键！）
copy "%GST_ROOT%\lib\gstreamer-1.0\gstlibav.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstnvcodec.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstqsv.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstamfcodec.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: WebRTC 插件
copy "%GST_ROOT%\lib\gstreamer-1.0\gstwebrtc.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstrtp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstrtpmanager.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstnice.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstdtls.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsrtp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsctp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1

echo [9/9] 生成 install.bat（ZIP 分发用，安装 zjc_worker 服务）...
(
echo @echo off
echo chcp 65001 ^>nul
echo echo ========================================
echo echo    Phoenix 安装/更新助手
echo echo ========================================
echo echo.
echo echo 正在停止旧服务...
echo sc stop zjc_worker ^>nul 2^>^&1
echo timeout /t 3 /nobreak ^>nul
echo taskkill /F /IM zjc_worker.exe ^>nul 2^>^&1
echo timeout /t 1 /nobreak ^>nul
echo echo 正在安装 zjc_worker 系统服务（需要管理员权限）...
echo echo.
echo "%%~dp0zjc_worker.exe" --install
echo if %%errorlevel%% equ 0 ^(
echo     echo.
echo     echo     [成功] zjc_worker 服务已安装并启动
echo ^) else ^(
echo     echo.
echo     echo     [失败] 服务安装失败，请右键"以管理员身份运行"此脚本
echo ^)
echo echo.
echo echo ========================================
echo echo    完成！zjc_worker 服务将开机自启
echo echo ========================================
echo echo.
echo pause
) > "%RELEASE_DIR%\install.bat"
echo     已生成 install.bat（服务安装版）

echo.
echo ========================================
echo    文件复制完成！
echo ========================================
echo.

:: 检查是否安装了 Inno Setup
if exist %INNO_SETUP% (
    echo [9/9] 生成安装程序...
    
    :: 创建输出目录
    if not exist "%INSTALLER_OUTPUT%" mkdir "%INSTALLER_OUTPUT%"
    
    :: 调用 Inno Setup 编译安装程序
    %INNO_SETUP% "%INSTALLER_SCRIPT%"
    
    if %errorlevel% equ 0 (
        echo.
        echo ========================================
        echo    安装程序生成成功！
        echo ========================================
        echo.
        echo 安装程序位置: %INSTALLER_OUTPUT%\Phoenix_Setup_1.0.0.exe
        echo.
        :: 打开安装程序目录
        explorer "%INSTALLER_OUTPUT%"
    ) else (
        echo.
        echo [警告] 安装程序生成失败！
        echo 请检查 Inno Setup 是否正确安装。
        echo.
        echo 发布目录（可手动压缩分发）: %RELEASE_DIR%
        explorer "%RELEASE_DIR%"
    )
) else (
    echo.
    echo [提示] 未检测到 Inno Setup，跳过安装程序生成。
    echo.
    echo 如需生成安装程序，请安装 Inno Setup 6:
    echo https://jrsoftware.org/isdl.php
    echo.
    echo ========================================
    echo    打包完成（仅文件复制）！
    echo ========================================
    echo.
    echo 发布目录: %RELEASE_DIR%
    echo GStreamer 插件: %RELEASE_DIR%\runtime\gstreamer\
    echo.
    :: 打开发布目录
    explorer "%RELEASE_DIR%"
)

echo.
echo 按任意键退出...
pause >nul
