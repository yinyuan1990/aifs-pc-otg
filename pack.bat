@echo off
chcp 936 >nul
setlocal enabledelayedexpansion

echo ========================================
echo    ����Otg�汾 (PhoenixOTG) �������
echo ========================================
echo.

rem ? ��56.23 OTG ר�����ű��������� pack.bat ���䣩��
rem    exe=PhoenixOTG.exe���ֿ�·��=aifs-pc-otg���ϴ�Ŀ¼=updatesoft/otg/ �� ������ȫ�ֿ���
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

:: ��� Release �汾�Ƿ����
if not exist "%BUILD_DIR%\PhoenixOTG.exe" (
    echo [����] δ�ҵ� Release �汾�� PhoenixOTG.exe
    echo ������ Qt Creator ���л��� Release ģʽ�����룡
    echo.
    pause
    exit /b 1
)

echo [0/8] ��� VC++ ���п�...
if not exist "%VCREDIST_PATH%" (
    echo     δ�ҵ����� VC++ ���п⣬��������...
    if not exist "D:\javafx\Acard\aic\aifs-pc-otg\redist" mkdir "D:\javafx\Acard\aic\aifs-pc-otg\redist"
    powershell -Command "Invoke-WebRequest -Uri '%VCREDIST_URL%' -OutFile '%VCREDIST_PATH%'"
    if exist "%VCREDIST_PATH%" (
        echo     VC++ ���п�������ɣ�
    ) else (
        echo     [����] VC++ ���п�����ʧ�ܣ����ֶ����ز����õ�:
        echo     %VCREDIST_PATH%
    )
) else (
    echo     VC++ ���п��Ѵ���
)

echo [1/8] ��������������Ŀ¼...
if exist "%RELEASE_DIR%" rmdir /s /q "%RELEASE_DIR%"
mkdir "%RELEASE_DIR%"

echo [2/8] ����������...
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

echo [3/8] ���� Qt �������������Ҫһ��ʱ�䣩...
"%QT_DIR%\windeployqt.exe" --release --qmldir D:\javafx\Acard\aic\aifs-pc-otg "%RELEASE_DIR%\PhoenixOTG.exe" >nul 2>&1

echo [3.5/8] ���� Qt WebEngine ����ʱ���ں˲�����ͼ�����κ�������װ Qt ���ɲ⣩...
:: WebEngine ������ͨ DLL ������Chromium �ӽ��� QtWebEngineProcess.exe + resources(*.pak/icudtl.dat/v8) + locales �����ֶ������
:: ����ûװ Qt �Ļ����ϡ��ں˲��ԡ���ͼֱ�Ӻ���/�򲻿���������ʽ����ȫ�ף�ȱʧ��������������
set QT_ROOT=D:\javafx\Acard\aic\qt\6.10.3\msvc2022_64
if exist "%QT_DIR%\Qt6WebEngineCore.dll" (
    :: 1) WebEngine / WebChannel ���� DLL���� release��������β d �ĵ��԰棩
    copy "%QT_DIR%\Qt6WebEngineCore.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebEngineQuick.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebEngineQuickDelegatesQml.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebEngineWidgets.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebChannel.dll" "%RELEASE_DIR%\" >nul 2>&1
    copy "%QT_DIR%\Qt6WebChannelQuick.dll" "%RELEASE_DIR%\" >nul 2>&1
    :: 2) Chromium �ӽ���
    copy "%QT_DIR%\QtWebEngineProcess.exe" "%RELEASE_DIR%\" >nul 2>&1
    :: 3) Chromium ��Դ��icudtl.dat + release .pak + v8 ���գ�����������ͬ�� resources\
    if not exist "%RELEASE_DIR%\resources" mkdir "%RELEASE_DIR%\resources"
    copy "%QT_ROOT%\resources\icudtl.dat" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_resources.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_resources_100p.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_resources_200p.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\qtwebengine_devtools_resources.pak" "%RELEASE_DIR%\resources\" >nul 2>&1
    copy "%QT_ROOT%\resources\v8_context_snapshot.bin" "%RELEASE_DIR%\resources\" >nul 2>&1
    :: 4) ���԰���ȱ�� Chromium ������澯/���ֹ����쳣��
    if not exist "%RELEASE_DIR%\translations\qtwebengine_locales" mkdir "%RELEASE_DIR%\translations\qtwebengine_locales"
    xcopy "%QT_ROOT%\translations\qtwebengine_locales\*.pak" "%RELEASE_DIR%\translations\qtwebengine_locales\" /Q /Y >nul 2>&1
    :: 5) QML ģ�飨windeployqt ��һ����ȫ����ʽ�� release �����
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
        echo     �Ѹ��� Qt WebEngine ����ʱ��DLL + Process + resources + locales + QML ģ�飩
    ) else (
        echo     [����] QtWebEngineProcess.exe ����ʧ�ܣ��ں˲�����ͼ�����޷���
    )
) else (
    echo     [��ʾ] δ�ҵ� Qt6WebEngineCore.dll��Qt δװ WebEngine ��������δ���� HAVE_KERNEL_TEST���������� WebEngine ���
)

echo [4/8] ���� VC++ ���пⰲװ����...
if exist "%VCREDIST_PATH%" (
    copy "%VCREDIST_PATH%" "%RELEASE_DIR%\" >nul 2>&1
    echo     �Ѹ��� vc_redist.x64.exe
) else (
    echo     [����] VC++ ���пⲻ���ڣ�����
)

echo [5/8] ���� FFmpeg DLL����ɫ�������ã�...
rem ͨ�临��ȫ�� ffmpeg DLL����д���汾�ţ��������������ֶԲ���ȱ DLL��
copy "%FFMPEG_BIN%\av*.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%FFMPEG_BIN%\sw*.dll" "%RELEASE_DIR%\" >nul 2>&1
rem �� exe �Ǿɰ汾(�������� ffmpeg)������Ŀ¼���ͬ�� DLL Ҳһ�����ϣ��汾���ף�
copy "%BUILD_DIR%\av*.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%BUILD_DIR%\sw*.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [6/8] ���� turbojpeg DLL...
copy "%VCPKG_BIN%\turbojpeg.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [6.5/8] ���� ONNX Runtime + AI ģ�ͣ���λ��ʶ��...
set ONNXRUNTIME_LIB=C:\onnxruntime\lib
copy "%ONNXRUNTIME_LIB%\onnxruntime.dll" "%RELEASE_DIR%\" >nul 2>&1
if exist "%RELEASE_DIR%\onnxruntime.dll" (
    echo     �Ѹ��� onnxruntime.dll
) else (
    echo     [����] onnxruntime.dll δ�ҵ���AI ��λ��ʶ�𽫲����ã�ȷ�� C:\onnxruntime\lib��
)
if not exist "%RELEASE_DIR%\models" mkdir "%RELEASE_DIR%\models"
copy "D:\javafx\Acard\aic\aifs-pc-otg\resources\models\cardYolov8.onnx" "%RELEASE_DIR%\models\" >nul 2>&1
if exist "%RELEASE_DIR%\models\cardYolov8.onnx" (
    echo     �Ѹ��� cardYolov8.onnx �� models\
) else (
    echo     [����] cardYolov8.onnx δ�ҵ���ȷ�� resources\models\��
)

echo [7/8] ���� GStreamer ����ʱ...
:: GStreamer ���� DLL
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
:: GStreamer WebRTC ��� DLL
copy "%GST_ROOT%\bin\gstsdp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstrtp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstwebrtc-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstwebrtcnice-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\nice-10.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\libcrypto-3-x64.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\libssl-3-x64.dll" "%RELEASE_DIR%\" >nul 2>&1
:: �������� DLL
copy "%GST_ROOT%\bin\orc-0.4-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstpbutils-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstaudio-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gsttag-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstnet-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1
copy "%GST_ROOT%\bin\gstsctp-1.0-0.dll" "%RELEASE_DIR%\" >nul 2>&1

echo [8/8] ���� GStreamer ����������� + JPEG ���룩...
:: ���� GStreamer ����ʱĿ¼�ṹ
mkdir "%RELEASE_DIR%\runtime\gstreamer\bin" >nul 2>&1
mkdir "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0" >nul 2>&1
mkdir "%RELEASE_DIR%\runtime\gstreamer\libexec\gstreamer-1.0" >nul 2>&1

:: ���Ʋ��ɨ����
copy "%GST_ROOT%\libexec\gstreamer-1.0\gst-plugin-scanner.exe" "%RELEASE_DIR%\runtime\gstreamer\libexec\gstreamer-1.0\" >nul 2>&1

:: ���ƺ��� DLL �� runtime Ŀ¼�������ʹ�ã�
xcopy "%GST_ROOT%\bin\*.dll" "%RELEASE_DIR%\runtime\gstreamer\bin\" /Q /Y >nul 2>&1

:: ���Ʊ���Ĳ��
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
:: ? ������������ؼ�����
copy "%GST_ROOT%\lib\gstreamer-1.0\gstlibav.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstnvcodec.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstqsv.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstamfcodec.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: Media Foundation �����������mfh264enc IDR ���룩
copy "%GST_ROOT%\lib\gstreamer-1.0\gstmediafoundation.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: WebRTC ���
copy "%GST_ROOT%\lib\gstreamer-1.0\gstwebrtc.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstrtp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstrtpmanager.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstnice.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstdtls.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsrtp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsctp.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: SRT ���������srtsrc + tsdemux��libsrt.dll �������� xcopy bin ���� runtime\bin��
copy "%GST_ROOT%\lib\gstreamer-1.0\gstsrt.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
copy "%GST_ROOT%\lib\gstreamer-1.0\gstmpegtsdemux.dll" "%RELEASE_DIR%\runtime\gstreamer\lib\gstreamer-1.0\" >nul 2>&1
:: SRT ����ʱ�⣨��ʽ���׵�������ͬ������ xcopy bin ʧ��ʱȱ libsrt��
copy "%GST_ROOT%\bin\libsrt.dll" "%RELEASE_DIR%\" >nul 2>&1

echo.
echo ========================================
echo    �ļ�������ɣ���zjc_worker_otg �ѷ��룬�� PC �˵�¼��� CDN �Զ���װ���������
echo ========================================
echo.

:: ? ��43 �嵥�������£��� CMakeLists.txt ���汾�ţ���һ��Դ�������� manifest.json
:: OTG �� make_manifest.ps1 Ĭ�� baseUrl ���� updatesoft/otg/v{VERSION}
echo [�嵥����] ��ȡ�汾�Ų����� manifest.json ...
set "APP_VERSION="
for /f tokens^=2^ delims^=^" %%v in ('findstr /c:"set(PHOENIX_APP_VERSION" "D:\javafx\Acard\aic\aifs-pc-otg\CMakeLists.txt"') do set "APP_VERSION=%%v"
if not defined APP_VERSION (
    echo     [����] δ�ܴ� CMakeLists.txt ���� PHOENIX_APP_VERSION������ manifest ���ɣ�
) else (
    echo     �汾��: !APP_VERSION!
    powershell -NoProfile -ExecutionPolicy Bypass -File "D:\javafx\Acard\aic\aifs-pc-otg\make_manifest.ps1" -ReleaseDir "%RELEASE_DIR%" -Version "!APP_VERSION!"
    if exist "%RELEASE_DIR%\manifest.json" (
        echo     manifest.json ������
        echo     [�ϴ�1] release ����Ŀ¼ ���� http://dl.147258yql.cn/updatesoft/otg/v!APP_VERSION!/
        echo     [�ϴ�2] yqlversion_new.json ��� changelog �󸲸� updatesoft/otg/yqlversion.json
    ) else (
        echo     [����] manifest.json ����ʧ�ܣ�
    )
)
echo.

:: ? �����Զ������õ� release.zip���ļ���zip��Ŀ¼�������ļ��У�legacy ȫ������ + ��װ���ã�
echo [�Զ�����] ���� release.zip ...
set RELEASE_ZIP=D:\javafx\Acard\aic\aifs-pc-otg\release.zip
if exist "%RELEASE_ZIP%" del "%RELEASE_ZIP%"
pushd "%RELEASE_DIR%"
tar -a -c -f "%RELEASE_ZIP%" *
popd
if exist "%RELEASE_ZIP%" (
    echo     release.zip ������: %RELEASE_ZIP%
    echo     ? �ϴ���������: http://dl.147258yql.cn/updatesoft/otg/release.zip
) else (
    echo     [����] release.zip ����ʧ�ܣ�
)
echo.

:: ��43 ��װ���汾�Ÿ��� CMakeLists �� PHOENIX_APP_VERSION��ǰ���ѽ����� APP_VERSION��
set "SETUP_VER=1.0.0"
if defined APP_VERSION set "SETUP_VER=%APP_VERSION%"
set "OUT_EXE=%INSTALLER_OUTPUT%\PhoenixOTG_Setup_%SETUP_VER%.exe"

:: ����Ƿ�װ�� Inno Setup
if exist %INNO_SETUP% (
    echo [9/9] ���ɰ�װ����...

    :: �������Ŀ¼
    if not exist "%INSTALLER_OUTPUT%" mkdir "%INSTALLER_OUTPUT%"

    :: ��43 ��"�ٳɹ�"������ǰ��ͬ���ɰ�װ��Ų�� .old������� rc=0 �����ļ����ڲ���ɹ���
    if exist "%OUT_EXE%.old" del "%OUT_EXE%.old" >nul 2>&1
    if exist "%OUT_EXE%" ren "%OUT_EXE%" "PhoenixOTG_Setup_%SETUP_VER%.exe.old" >nul 2>&1

    :: ���� Inno Setup ���루/DMyAppVersion ���� iss �ڶ��װ汾�ţ���װ���ļ���ͬ�����汾��
    %INNO_SETUP% /DMyAppVersion=%SETUP_VER% "%INSTALLER_SCRIPT%"
    set "ISCC_RC=!errorlevel!"

    if not "!ISCC_RC!"=="0" (
        echo.
        echo [����] ��װ��������ʧ�ܣ�ISCC �˳���=!ISCC_RC!
        echo �����Ϸ��� ISCC ����� Error �У�����ԭ��Դ�ļ�ȱʧ / ɱ���������� Output Ŀ¼��
        echo ������� %INSTALLER_OUTPUT% ����ɱ�������ų�������ԣ���
        echo ��һ�氲װ��������: %OUT_EXE%.old
        echo.
        echo ����Ŀ¼�����ֶ�ѹ���ַ���: %RELEASE_DIR%
        explorer "%RELEASE_DIR%"
    ) else if not exist "%OUT_EXE%" (
        echo.
        echo [����] ISCC �˳���=0����û�в��� %OUT_EXE% ��
        echo �����Ϸ����Ƿ��� "Compile aborted" / "Error in ..." ��������һ�汣���� .old��
        echo.
        echo ����Ŀ¼�����ֶ�ѹ���ַ���: %RELEASE_DIR%
        explorer "%RELEASE_DIR%"
    ) else (
        if exist "%OUT_EXE%.old" del "%OUT_EXE%.old" >nul 2>&1
        echo.
        echo ========================================
        echo    ��װ�������ɳɹ���
        echo ========================================
        echo.
        echo ��װ����λ��: %OUT_EXE%
        echo ��ǿ��/���ص�ַ���ܺ�̨ PC-OTG ����updatesoft/otg/PhoenixOTG_Setup_%SETUP_VER%.exe��
        echo.
        :: �򿪰�װ����Ŀ¼
        explorer "%INSTALLER_OUTPUT%"
    )
) else (
    echo.
    echo [��ʾ] δ��⵽ Inno Setup��������װ�������ɡ�
    echo.
    echo �������ɰ�װ�����밲װ Inno Setup 6:
    echo https://jrsoftware.org/isdl.php
    echo.
    echo ========================================
    echo    �����ɣ����ļ����ƣ���
    echo ========================================
    echo.
    echo ����Ŀ¼: %RELEASE_DIR%
    echo GStreamer ���: %RELEASE_DIR%\runtime\gstreamer\
    echo.
    :: �򿪷���Ŀ¼
    explorer "%RELEASE_DIR%"
)

echo.
echo ��������˳�...
pause >nul
