@echo off
chcp 65001 >nul
rem ============================================================
rem  vivo TWS Air3 Pro 手表控制器 - 免 Gradle 手动构建脚本
rem  依赖: %USERPROFILE%\android-tools\jdk-ms\bin (JDK 17)
rem        %LOCALAPPDATA%\Android\Sdk (platforms;android-34,
rem        build-tools;34.0.0, 由 scripts\setup-env.bat 安装)
rem  产物: vivo-tws-watch-debug.apk
rem ============================================================
setlocal
cd /d "%~dp0.."

set JDK_DIR=
for /d %%d in ("%USERPROFILE%\android-tools\jdk-*") do set JDK_DIR=%%d
if not defined JDK_DIR (
  echo [X] 未找到 JDK, 先运行 scripts\setup-env.bat
  exit /b 1
)
set PATH=%JDK_DIR%\bin;%PATH%

set SDK=%LOCALAPPDATA%\Android\Sdk
set BT=%SDK%\build-tools\34.0.0
set PLAT=%SDK%\platforms\android-34\android.jar
if not exist "%PLAT%" (
  echo [X] 未找到 android.jar, 先运行 scripts\setup-env.bat
  exit /b 1
)
rem 磁贴库(androidx.wear.tiles)及依赖, 由 scripts\fetch-libs.bat 下载到 libs\
set LIBS=%~dp0..\libs
set CP_JARS=%LIBS%\tiles-1.1.0.jar;%LIBS%\tiles-proto-1.1.0.jar;%LIBS%\annotation-1.2.0.jar;%LIBS%\annotation-experimental-1.2.0.jar;%LIBS%\listenablefuture-1.0.jar
if not exist "%LIBS%\tiles-1.1.0.jar" (
  echo [X] 未找到 libs\tiles-1.1.0.jar, 先运行 scripts\fetch-libs.bat
  exit /b 1
)

if not exist keystore\debug.keystore (
  mkdir keystore 2>nul
  keytool -genkeypair -keystore keystore\debug.keystore ^
    -alias androiddebugkey -storepass android -keypass android ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -dname "CN=Android Debug,O=Android,C=US" || exit /b 1
)

echo [1/6] aapt2 compile...
if exist build rmdir /s /q build
mkdir build
"%BT%\aapt2.exe" compile --dir res -o build\res.zip || exit /b 1

echo [2/6] aapt2 link...
"%BT%\aapt2.exe" link -o build\app.unsigned.apk -I "%PLAT%" ^
  --manifest AndroidManifest.xml --java build\gen ^
  --min-sdk-version 30 --target-sdk-version 34 ^
  --version-code 12 --version-name 0.3.0 ^
  --auto-add-overlay build\res.zip || exit /b 1

echo [3/6] javac...
setlocal enabledelayedexpansion
set SRCS=
for /r src %%f in (*.java) do set "SRCS=!SRCS! "%%f""
for /r build\gen %%f in (*.java) do set "SRCS=!SRCS! "%%f""
javac -source 8 -target 8 -encoding UTF-8 ^
  -bootclasspath "%PLAT%" -classpath "%CP_JARS%" -d build\classes !SRCS! || exit /b 1
endlocal

echo [4/6] d8 dex...
pushd build\classes
jar cf ..\classes.jar com || exit /b 1
popd
rem 库 jar 必须作为输入一起进 dex(--lib 只用于解析引用,不会打包)
setlocal enabledelayedexpansion
set D8IN=build\classes.jar
for %%j in ("%LIBS%\*.jar") do set "D8IN=!D8IN! "%%j""
call "%BT%\d8.bat" --release --lib "%PLAT%" --min-api 30 ^
  --output build !D8IN! || exit /b 1
endlocal

echo [5/6] 打包 dex...
powershell -NoProfile -Command ^
  "Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem; $z=[System.IO.Compression.ZipFile]::Open('build\app.unsigned.apk','Update'); Get-ChildItem 'build' -Filter '*.dex' | ForEach-Object { $e=$z.CreateEntry($_.Name); $s=$e.Open(); $f=[System.IO.File]::OpenRead($_.FullName); $f.CopyTo($s); $f.Close(); $s.Close() }; $z.Dispose()" || exit /b 1

echo [6/6] 对齐 + 签名...
"%BT%\zipalign.exe" -f 4 build\app.unsigned.apk build\app.aligned.apk || exit /b 1
call "%BT%\apksigner.bat" sign --ks keystore\debug.keystore ^
  --ks-pass pass:android --key-pass pass:android ^
  --ks-key-alias androiddebugkey ^
  --out vivo-tws-watch-debug.apk build\app.aligned.apk || exit /b 1

echo.
echo [OK] 已生成 vivo-tws-watch-debug.apk
endlocal
