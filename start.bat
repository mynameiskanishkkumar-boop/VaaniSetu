@echo off
chcp 65001 >nul
setlocal
title VaaniSetu - Offline Multilingual Voice Transceiver (SIH 2026)

cls
echo ===============================================================================
echo                VAANISETU - OFFLINE MULTILINGUAL VOICE TRANSCEIVER
echo                    Smart India Hackathon 2026 ^| ISRO PS 26173
echo                          "Speak. Convert. Connect. Listen."
echo ===============================================================================
echo.

cd /d "%~dp0"

if not defined JAVA_HOME (
    if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" (
        set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
    ) else if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)
if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

echo [1/3] Checking Java Environment...
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please ensure JAVA_HOME is set.
    goto end_pause
)

echo [OK] Using dedicated OpenJDK 17 LTS:
"%JAVA_HOME%\bin\java.exe" -version 2>&1
echo.

echo [2/3] Checking Android SDK...
if defined ANDROID_HOME (
    echo [OK] ANDROID_HOME: %ANDROID_HOME%
) else (
    if defined ANDROID_SDK_ROOT (
        echo [OK] ANDROID_SDK_ROOT: %ANDROID_SDK_ROOT%
    ) else (
        echo [INFO] ANDROID_HOME is not set in environment variables.
        echo Set ANDROID_HOME if building against a local SDK installation.
    )
)
echo.

echo [3/3] System Environment Check Complete.
echo.
echo ===============================================================================
echo                             VAANISETU CONTROL MENU
echo ===============================================================================
echo  [1] Run Automated Unit Tests (Packet Framing, VAD FSM, WER Accuracy)
echo  [2] Build Debug APK (assembleDebug)
echo  [3] Install and Launch on Connected Device (installDebug)
echo  [4] Run Gradle Build Verification (tasks)
echo  [5] View Project Documentation Reference
echo  [6] Exit
echo ===============================================================================
echo.

choice /c 123456 /n /m "Select an option [1-6]: "
set CHOICE=%ERRORLEVEL%

if "%CHOICE%"=="1" goto run_tests
if "%CHOICE%"=="2" goto build_apk
if "%CHOICE%"=="3" goto install_apk
if "%CHOICE%"=="4" goto run_check
if "%CHOICE%"=="5" goto open_docs
if "%CHOICE%"=="6" goto exit_app

goto end_pause

:run_tests
echo.
echo [*] Executing VaaniSetu Unit Tests...
call gradlew.bat test --info
goto end_pause

:build_apk
echo.
echo [*] Building VaaniSetu Debug APK...
call gradlew.bat assembleDebug
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo [SUCCESS] APK built successfully at:
    echo app\build\outputs\apk\debug\app-debug.apk
)
goto end_pause

:install_apk
echo.
echo [*] Installing and launching on connected Android device...
call gradlew.bat installDebug
goto end_pause

:run_check
echo.
echo [*] Running Gradle tasks verification...
call gradlew.bat tasks
goto end_pause

:open_docs
echo.
echo ===============================================================================
echo                           PROJECT DOCUMENTATION
echo ===============================================================================
echo  - README.md:           Project overview and SIH problem statement mapping
echo  - ARCHITECTURE.md:     System architecture and memory management
echo  - NETWORK_PROTOCOL.md: Low-bandwidth binary framing specification
echo  - MODEL_AUDIT.md:      10-language model audit matrix and licenses
echo  - DEMO_GUIDE.md:       Two-phone offline demonstration protocol
echo ===============================================================================
goto end_pause

:end_pause
echo.
pause
goto exit_app

:exit_app
exit /b 0
