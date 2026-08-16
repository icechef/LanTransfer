@echo off
rem Build the Android APK. Double-click this file, or run it in a normal
rem Command Prompt (NOT inside the sandboxed assistant, which blocks the
rem Windows cert store access that the Java toolchain needs).
setlocal

set "JAVA_HOME=C:\Users\zhang\.workbuddy\binaries\jdk-17.0.20+8"
set "ANDROID_HOME=C:\Users\zhang\.workbuddy\binaries\android-sdk"
set "ANDROID_SDK_ROOT=C:\Users\zhang\.workbuddy\binaries\android-sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "D:\Users\zhang\Documents\git\transport_tools\android"

echo Building APK...
call "C:\Users\zhang\.workbuddy\binaries\gradle-8.9\bin\gradle.bat" assembleDebug --no-daemon
echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo DONE: app\build\outputs\apk\debug\app-debug.apk
  copy /Y "app\build\outputs\apk\debug\app-debug.apk" "..\dist\LanTransfer-debug.apk" >nul
  echo Copied to dist\LanTransfer-debug.apk
) else (
  echo BUILD FAILED. See error messages above.
)
echo.
pause
