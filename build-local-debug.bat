@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "GRADLE_USER_HOME=%PROJECT_DIR%.gradle-user-home"
set "ANDROID_USER_HOME=%PROJECT_DIR%.android-user-home"

if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

call "%PROJECT_DIR%gradlew.bat" --gradle-user-home "%GRADLE_USER_HOME%" --no-daemon --console=plain -PcodexBuildDir="%PROJECT_DIR%.codex-build\app" :app:assembleDebug
