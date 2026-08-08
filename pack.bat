@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ========================================
echo    看家Otg版本 (PhoenixOTG) 打包工具
echo ========================================
echo.

rem ⭐ §56.23 OTG 专版打包脚本（从主版 pack.bat 适配）：
rem    exe=PhoenixOTG.exe、仓库路径=aifs-pc-otg、上传目录=updatesoft/otg/ 与 主版完全分开。
set RELEASE_DIR=D:\javafx\Acard\aic\aifs-pc-otg\release
set BUILD_DIR=D:\javafx\Acard\aic\aifs-pc-otg\build\Desktop_Qt_6_10_3_MSVC2022_64bit-Release
set QT_DIR=D:\javafx\Acard\aic\qt\6.10.3\msvc2022_64\bin
set FFMPEG_BIN=C:\ffmpeg\bin
set VCPKG_BIN=D:\javafx\Acard\aic\vcpkg\installed\x64-windows\bin
set GST_ROOT=C:\Program Files\gstreamer\1.0\msvc_x86_64
set INNO_SETUP="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
set INSTALLER_SCRIPT=D:\javafx\Acard\aic\aifs-pc-otg\installer.iss
set INSTALLER_OUTPUT=D:\javafx\Acard\aic\aifs-pc-otg\installer_output
set VCREDIST_PATH=D:\javafx\Acard\aic\aifs-pc-otg\redist\vc_redist.x64.exe
set VCREDIST_URL=https://aka.ms/vs/17/release/vc_redist.x64.exe

:: 检查 Release 版本是否存在
if not exist "%BUILD_DIR%\PhoenixOTG.exe" (
    echo [错误] 未找到 Release 版本的 PhoenixOTG.exe
    echo 请先在 Qt Creator 中切换到 Release 模式并编译！
    echo.
    pause
    exit /b 1
)

echo [0/8] 检查 VC++ 运行库...
if not exist "%VCREDIST_PATH%" (
    echo     未找到本地 VC++ 运行库，正在下载...
    if not exist "D:\javafx\Acard\aic\aifs-pc-otg\redist" mkdir "D:\javafx\Acard\aic\aifs-pc-otg\redist"
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
copy "%BUILD_DIR%\PhoenixOTG.exe" "%RELEASE_DIR%\" >nul
rem zjc worker is separated: NOT bundled, PC downloads+installs zjc_worker_otg from CDN after login.

rem ---- Copy MSVC runtime DLLs (app-local, fixes VCRUNTIME140_1.dll missing on clean PCs) ----
set "VC_CRT="
for /d %%d in ("D:\soft\vs\Community\VC\Redist\MSVC\*") do (
    if exist "%%d\x64\Microsoft.VC143.CRT\vcruntime140_1.dll" set "VC_CRT=%%d\x64\Microsoft.VC143.CRT"
)
if defined VC_CRT (
    copy "%VC_CRT%\*.dll" "%RELEASE_DIR%\" >nul 2>&1
    echo     Copied MSVC CRT runtime from %VC_CRT%
) else (
    copy "%SystemRoot%\System32\vcruntime140.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%SystemRoot%\System32\vcruntime140_1.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%SystemRoot%\System32\msvcp140.dll" "%RELEASE_DIR%\" >nul 2>&1
    echo     VC Redist folder not found, copied CRT from System32 fallback
)

echo [3/8] 部署 Qt 依赖（这可能需要一点时间）...
"%QT_DIR%\windeployqt.exe" --release --qmldir D:\javafx\Acard\aic\aifs-pc-otg "%RELEASE_DIR%\PhoenixOTG.exe" >nul 2>&1

echo [3.5/8] 部署 Qt WebEngine 运行时（内核测试视图，让任何人无需装 Qt 即可测）...
:: WebEngine 不是普通 DLL 依赖：Chromium 子进程 QtWebEngineProcess.exe + resources(*.pak/icudtl.dat/v8) + locales 必须手动随包，
:: 否则没装 Qt 的机器上「内核测试」视图直接黑屏/打不开。这里显式复制全套，缺失则跳过不报错。
set QT_ROOT=D:\javafx\Acard\aic\qt\6.10.3\msvc2022_64
if exist "%QT_DIR%\Qt6WebEngineCore.dll" (
    :: 1) WebEngine / WebChannel 核心 DLL（仅 release，不带结尾 d 的调试版）
    copy "%QT_DIR%\Qt6WebEngineCore.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebEngineQuick.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebEngineQuickDelegatesQml.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebEngineWidgets.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebChannel.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebChannelQuick.dll" "%RELEASE_DIR%\" >nul 2>&1
    :: 2) Chromium 子进程
    copy "%QT_DIR%\QtWebEngineProcess.exe" "%RELEASE_DIR%\" >nul 2>&1
    :: 3) Chromium 资源（icudtl.dat + release .pak + v8 快照），放主程序同级 resources\
    if not exist "%RELEASE_DIR%\resources" mkdir "%RELEASE_DIR%\resources"
    copy "%QT_ROOT%\resources\icudtl.dat" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_resources.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_resources_100p.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_resources_200p.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_devtools_resources.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\v8_context_snapshot.bin" "%RELEASE_DIR%\resources\" >nul 2>&1
    :: 4) 语言包（缺它 Chromium 启动会告警/部分功能异常）
    if not exist "%RELEASE_DIR%\translations\qtwebengine_locales" mkdir "%RELEASE_DIR%\translations\qtwebengine_locales"
    xcopy "%QT_ROOT%\translations\qtwebengine_locales\*.pak" "%RELEASE_DIR%\translations\qtwebengine_locales\" /Q /Y >nul 2>&1
    :: 5) QML 模块（windeployqt 不一定带全，显式补 release 插件）
    if not exist "%RELEASE_DIR%\QtWebEngine" mkdir "%RELEASE_DIR%\QtWebEngine"
    copy "%QT_ROOT%\qml\QtWebEngine\qmldir" "%RELEASE_DIR%\QtWebEngine\" >nul 2>&1
    copy "%QT_ROOT%\qml\QtWebEngine\plugins.qmltypes" "%RELEASE_DIR%\QtWebEngine\" >nul 2>&1
    copy "%QT_ROOT%\qml\QtWebEngine\qtwebenginequickplugin.dll" "%RELEASE_DIR%\QtWebEngine\" >nul 2>&1
    if exist "%QT_ROOT%\qml\QtWebEngine\ControlsDelegates" xcopy "%QT_ROOT%\qml\QtWebEngine\ControlsDelegates" "%RELEASE_DIR%\QtWebEngine\ControlsDelegates\" /E /I /Q /Y >nul 2>&1
    if not exist "%RELEASE_DIR%\QtWebChannel" mkdir "%RELEASE_DIR%\QtWebChannel"
    copy "%QT_ROOT%\qml\QtWebChannel\qmldir" "%RELEASE_DIR%\QtWebChannel\" >nul 2>&1
    copy "%QT_ROOT%\qml\QtWebChannel\plugins.qmltypes" "%RELEASE_DIR%\QtWebChannel\" >nul 2>&1
    copy "%QT_ROOT%\qml\QtWebChannel\webchannelquickplugin.dll" "%RELEASE_DIR%\QtWebChannel\" >nul 2>&1
    if exist "%RELEASE_DIR%\QtWebEngineProcess.exe" (
        echo     已复制 Qt WebEngine 运行时（DLL + Process + resources + locales + QML 模块）
    ) else (
        echo     [警告] QtWebEngineProcess.exe 复制失败，内核测试视图可能无法打开
    )
) else (
    echo     [提示] 未找到 Qt6WebEngineCore.dll（Qt 未装 WebEngine 或主程序未启用 HAVE_KERNEL_TEST），已跳过 WebEngine 打包
)

echo [4/8] 复制 VC++ 运行库安装程序...
if exist "%VCREDIST_PATH%" (
    copy "%VCREDIST_PATH%" "%RELEASE_DIR%\" >nul 2>&1
    echo     已复制 vc_redist.x64.exe
) else (
    echo     [警告] VC++ 运行库不存在，跳过
)

echo [5/8] 复制 FFmpeg DLL（颜色调整备用）...
rem 通配复制全部 ffmpeg DLL（不写死版本号，避免升级后名字对不上缺 DLL）
copy "%FFMPEG_BIN%\av*.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%FFMPEG_BIN%\sw*.dll" "%RELEASE_DIR%\" >nul 2>&1
rem 若 exe 是旧版本(链接了老 ffmpeg)，构建目录里的同名 DLL 也一并带上（版本兜底）
copy "%BUILD_DIR%\av*.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%BUILD_DIR%\sw*.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [6/8] 复制 turbojpeg DLL...
copy "%VCPKG_BIN%\turbojpeg.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [6.5/8] 复制 ONNX Runtime + AI 模型（牌位置识别）...
set ONNXRUNTIME_LIB=C:\onnxruntime\lib
copy "%ONNXRUNTIME_LIB%\onnxruntime.dll" "%RELEASE_DIR%\" >nul 2>&1
if exist "%RELEASE_DIR%\onnxruntime.dll" (
    echo     已复制 onnxruntime.dll
) else (
    echo     [警告] onnxruntime.dll 未找到，AI 牌位置识别将不可用（确认 C:\onnxruntime\lib）
)
if not exist "%RELEASE_DIR%\models" mkdir "%RELEASE_DIR%\models"
copy "D:\javafx\Acard\aic\aifs-pc-otg\resources\models\cardYolov8.onnx" "%RELEASE_DIR%\models\" >nul 2>&1
if exist "%RELEASE_DIR%\models\cardYolov8.onnx" (
    echo     已复制 cardYolov8.onnx 到 models\
) else (
    echo     [警告] cardYolov8.onnx 未找到（确认 resources\models\）
)

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
copy "%GST_ROOT%\lib\gstreamer-1.0\gstpng.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
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
:: Media Foundation 编码器插件（mfh264enc IDR 编码）
copy "%GST_ROOT%\lib\gstreamer-1.0\gstmediafoundation.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: WebRTC 插件
copy "%GST_ROOT%\lib\gstreamer-1.0\gstwebrtc.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstrtp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstrtpmanager.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstnice.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstdtls.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsrtp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsctp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: SRT 拉流插件（srtsrc + tsdemux；libsrt.dll 已随上面 xcopy bin 进入 runtime\bin）
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsrt.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstmpegtsdemux.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: SRT 运行时库（显式兜底到主程序同级，防 xcopy bin 失败时缺 libsrt）
copy "%GST_ROOT%\bin\libsrt.dll" "%RELEASE_DIR%\" >nul 2>&1

echo.
echo ========================================
echo    文件复制完成！（zjc_worker_otg 已分离，由 PC 端登录后从 CDN 自动安装，不随包）
echo ========================================
echo.

:: ⭐ §43 清单差量更新：从 CMakeLists.txt 读版本号（单一来源），生成 manifest.json
:: OTG 版 make_manifest.ps1 默认 baseUrl 已是 updatesoft/otg/v{VERSION}
echo [清单更新] 读取版本号并生成 manifest.json ...
set "APP_VERSION="
for /f tokens^=2^ delims^=^" %%v in ('findstr /c:"set(PHOENIX_APP_VERSION" "D:\javafx\Acard\aic\aifs-pc-otg\CMakeLists.txt"') do set "APP_VERSION=%%v"
if not defined APP_VERSION (
    echo     [警告] 未能从 CMakeLists.txt 解析 PHOENIX_APP_VERSION，跳过 manifest 生成！
) else (
    echo     版本号: !APP_VERSION!
    powershell -NoProfile -ExecutionPolicy Bypass -File "D:\javafx\Acard\aic\aifs-pc-otg\make_manifest.ps1" -ReleaseDir "%RELEASE_DIR%" -Version "!APP_VERSION!"
    if exist "%RELEASE_DIR%\manifest.json" (
        echo     manifest.json 已生成
        echo     [上传1] release 整个目录 传到 http://dl.147258yql.cn/updatesoft/otg/v!APP_VERSION!/
        echo     [上传2] yqlversion_new.json 填好 changelog 后覆盖 updatesoft/otg/yqlversion.json
    ) else (
        echo     [警告] manifest.json 生成失败！
    )
)
echo.

:: ⭐ 生成自动更新用的 release.zip（文件在zip根目录，无子文件夹；legacy 全量回退 + 首装包用）
echo [自动更新] 生成 release.zip ...
set RELEASE_ZIP=D:\javafx\Acard\aic\aifs-pc-otg\release.zip
if exist "%RELEASE_ZIP%" del "%RELEASE_ZIP%"
pushd "%RELEASE_DIR%"
tar -a -c -f "%RELEASE_ZIP%" *
popd
if exist "%RELEASE_ZIP%" (
    echo     release.zip 已生成: %RELEASE_ZIP%
    echo     ⚠ 上传到服务器: http://dl.147258yql.cn/updatesoft/otg/release.zip
) else (
    echo     [警告] release.zip 生成失败！
)
echo.

:: §43 安装包版本号跟随 CMakeLists 的 PHOENIX_APP_VERSION（前面已解析到 APP_VERSION）
set "SETUP_VER=1.0.0"
if defined APP_VERSION set "SETUP_VER=%APP_VERSION%"
set "OUT_EXE=%INSTALLER_OUTPUT%\PhoenixOTG_Setup_%SETUP_VER%.exe"

:: 检查是否安装了 Inno Setup
if exist %INNO_SETUP% (
    echo [9/9] 生成安装程序...

    :: 创建输出目录
    if not exist "%INSTALLER_OUTPUT%" mkdir "%INSTALLER_OUTPUT%"

    :: §43 防"假成功"：编译前把同名旧安装包挪成 .old，编译后 rc=0 且新文件存在才算成功。
    if exist "%OUT_EXE%.old" del "%OUT_EXE%.old" >nul 2>&1
    if exist "%OUT_EXE%" ren "%OUT_EXE%" "PhoenixOTG_Setup_%SETUP_VER%.exe.old" >nul 2>&1

    :: 调用 Inno Setup 编译（/DMyAppVersion 覆盖 iss 内兜底版本号，安装包文件名同步带版本）
    %INNO_SETUP% /DMyAppVersion=%SETUP_VER% "%INSTALLER_SCRIPT%"
    set "ISCC_RC=!errorlevel!"

    if not "!ISCC_RC!"=="0" (
        echo.
        echo [警告] 安装程序生成失败！ISCC 退出码=!ISCC_RC!
        echo 请往上翻看 ISCC 输出的 Error 行（常见原因：源文件缺失 / 杀毒软件锁定 Output 目录，
        echo 后者请把 %INSTALLER_OUTPUT% 加入杀毒软件排除项后重试）。
        echo 上一版安装包保留在: %OUT_EXE%.old
        echo.
        echo 发布目录（可手动压缩分发）: %RELEASE_DIR%
        explorer "%RELEASE_DIR%"
    ) else if not exist "%OUT_EXE%" (
        echo.
        echo [警告] ISCC 退出码=0，但没有产出 %OUT_EXE% ！
        echo 请往上翻看是否有 "Compile aborted" / "Error in ..." 字样；上一版保留在 .old。
        echo.
        echo 发布目录（可手动压缩分发）: %RELEASE_DIR%
        explorer "%RELEASE_DIR%"
    ) else (
        if exist "%OUT_EXE%.old" del "%OUT_EXE%.old" >nul 2>&1
        echo.
        echo ========================================
        echo    安装程序生成成功！
        echo ========================================
        echo.
        echo 安装程序位置: %OUT_EXE%
        echo （强更/下载地址填总后台 PC-OTG 栏：updatesoft/otg/PhoenixOTG_Setup_%SETUP_VER%.exe）
        echo.
        :: 打开安装程序目录
        explorer "%INSTALLER_OUTPUT%"
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
