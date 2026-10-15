@echo off
rem eye_turn_page release build: auto-creates keystore on first run (local only, gitignored). APK -> release\
setlocal
cd /d %~dp0

rem ponytail: local.properties 不進版控，路徑每台不同；缺了就從常見位置自動補，預設安裝不用手建
if not exist local.properties (
  set "SDK="
  if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "SDK=%ANDROID_HOME%"
  if not defined SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK=%ANDROID_SDK_ROOT%"
  if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk\platforms" set "SDK=%LOCALAPPDATA%\Android\Sdk"
  if not defined SDK if exist "C:\Android\Sdk\platforms" set "SDK=C:\Android\Sdk"
  if defined SDK (
    setlocal EnableDelayedExpansion
    echo sdk.dir=!SDK:\=/!> local.properties
    endlocal
    echo [0/3] SDK auto-detected, wrote local.properties
  )
)
if not exist local.properties echo [ERR] Android SDK not found. Set ANDROID_HOME or install SDK to %%LOCALAPPDATA%%\Android\Sdk. && exit /b 1

rem ponytail: 新版 Studio 的 jbr 常剩 runtime（沒 javac/keytool），優先拿 Gradle 自動裝的 toolchain JDK（有 javac 才算數）
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%d in ("%USERPROFILE%\.gradle\jdks\*") do if exist "%%d\bin\javac.exe" set "JDK=%%d"
if not defined JDK if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JDK=C:\Program Files\Android\Android Studio\jbr"
if defined JDK set "JAVA_HOME=%JDK%"
set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if not exist "%KEYTOOL%" for /f "delims=" %%k in ('where keytool 2^>nul') do set "KEYTOOL=%%k"
if not exist "%KEYTOOL%" echo [ERR] keytool not found. Install Android Studio or set JAVA_HOME. && exit /b 1

if not exist eye_turn_page-release.jks (
  echo [1/3] generating keystore - first run only...
  "%KEYTOOL%" -genkeypair -keystore eye_turn_page-release.jks -alias eyeturn -keyalg RSA -keysize 2048 -validity 9125 -storepass eyeturn-release -keypass eyeturn-release -dname "CN=EyeTurnPage" || exit /b 1
  (
    echo storeFile=eye_turn_page-release.jks
    echo storePassword=eyeturn-release
    echo keyAlias=eyeturn
    echo keyPassword=eyeturn-release
  ) > keystore.properties
)

echo [2/3] assembleRelease...
call gradlew.bat assembleRelease --console=plain || exit /b 1

set VER=unknown
for /f "tokens=2,3" %%a in ('findstr /r "versionName" app\build.gradle') do (
  set VER=%%a
  if not "%%b"=="" set VER=%%b
)
set VER=%VER:"=%
set VER=%VER:'=%
if not exist release mkdir release
copy /y app\build\outputs\apk\release\app-release.apk release\eye_turn_page-v%VER%.apk || exit /b 1
echo [3/3] done: release\eye_turn_page-v%VER%.apk
