@echo off
chcp 65001 >nul
setlocal

set FFMPEG=C:\ffmpeg\bin\ffmpeg.exe
set SRT_HOST=39.97.46.221
set SRT_PORT=10080
set STREAM=test

echo ========================================
echo   SRT 服务端推流自测 (live/%STREAM%)
echo   目标: srt://%SRT_HOST%:%SRT_PORT%
echo ========================================
echo.

"%FFMPEG%" -hide_banner -re -f lavfi -i "testsrc=size=1280x720:rate=25" -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -f mpegts "srt://%SRT_HOST%:%SRT_PORT%?streamid=#!::r=live/%STREAM%,m=publish"

echo.
echo [推流结束] 退出码: %errorlevel%
pause
