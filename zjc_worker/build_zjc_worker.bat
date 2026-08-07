@echo off
setlocal enabledelayedexpansion

REM ============================================================================
REM  Build zjc_worker_otg (OTG variant, paired with PhoenixOTG; section 56.22).
REM
REM  Just double-click. Outputs in build\ :
REM      zjc_worker_otg.exe   WinDivert.dll   WinDivert64.sys
REM  Upload those 3 files to  http://dl.147258yql.cn/updatesoft/zjcotg/
REM  then set the release version in the admin panel "zjc_worker" page (OTG tab).
REM
REM  Requires Visual Studio 2022 (C++ desktop workload + CMake tools).
REM  VS location is auto-detected via vswhere; no manual path edit needed.
REM  (All-ASCII on purpose: avoids GBK/UTF-8 batch-parsing garbling.)
REM ============================================================================

echo ======================================
echo   Locating Visual Studio ...
echo ======================================

set "VCVARS="

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        if exist "%%i\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%%i\VC\Auxiliary\Build\vcvars64.bat"
    )
)

if not defined VCVARS (
    for %%P in (
        "D:\soft\vs\Community\VC\Auxiliary\Build\vcvars64.bat"
        "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
        "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
        "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat"
        "C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat"
    ) do (
        if not defined VCVARS if exist %%P set "VCVARS=%%~P"
    )
)

if not defined VCVARS (
    echo [ERROR] vcvars64.bat not found.
    echo         Install VS 2022 with the "Desktop development with C++" workload,
    echo         or edit the fallback path in this script.
    goto :end
)
echo   Found: !VCVARS!

echo.
echo ======================================
echo   Init MSVC x64 build environment ...
echo ======================================
call "!VCVARS!" >nul
if errorlevel 1 (
    echo [ERROR] vcvars64.bat init failed
    goto :end
)

for %%D in (
    "%VSINSTALLDIR%Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin"
    "%VSINSTALLDIR%Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja"
) do (
    if exist "%%~D" set "PATH=%%~D;!PATH!"
)

where cmake >nul 2>nul
if errorlevel 1 (
    echo [ERROR] cmake not found. In VS Installer enable "C++ CMake tools for Windows",
    echo         or install CMake separately and add it to PATH.
    goto :end
)

cd /d "%~dp0"

echo.
echo ======================================
echo   Configure (CMake, Release) ...
echo ======================================
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
if errorlevel 1 (
    echo [ERROR] CMake configure failed (scroll up for details)
    goto :end
)

echo.
echo ======================================
echo   Building ...
echo ======================================
cmake --build build
if errorlevel 1 (
    echo [ERROR] Build failed (scroll up for details)
    goto :end
)

echo.
echo ======================================
echo   BUILD OK. Output dir: %~dp0build
echo ======================================
dir /b build\zjc_worker_otg.exe build\WinDivert.dll build\WinDivert64.sys 2>nul
echo.
echo Version:
build\zjc_worker_otg.exe --version
echo.
echo.
echo Upload the 3 files above to http://dl.147258yql.cn/updatesoft/zjcotg/

:end
echo.
echo ======================================
echo   Press any key to close ...
echo ======================================
pause >nul
endlocal
