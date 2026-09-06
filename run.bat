@echo off
rem Runs the built UI.jar. Its JAXB dependency jars are found through the jar's manifest
rem Class-Path, so they only need to sit next to it. The working directory is left
rem alone, so a relative path typed into the app resolves against wherever you are.
rem
rem JavaFX is not part of the JDK and cannot travel in the manifest, so the desktop UI needs
rem the SDK named on the module path - javafx.fxml as well as javafx.controls, because the
rem window's layout is read from DesktopApp.fxml. Set JAVAFX_HOME if yours lives elsewhere.
chcp 65001 >nul
if "%JAVAFX_HOME%"=="" set JAVAFX_HOME=%USERPROFILE%\javafx-sdk-25.0.4

if not exist "%JAVAFX_HOME%\lib" (
  echo JavaFX SDK not found at %JAVAFX_HOME%. 1>&2
  echo Download it from https://openjfx.io and set JAVAFX_HOME to where you put it. 1>&2
  exit /b 1
)

java --module-path "%JAVAFX_HOME%\lib" --add-modules javafx.controls,javafx.fxml ^
     --enable-native-access=javafx.graphics ^
     -jar "%~dp0out\artifacts\UI_jar\UI.jar" %*
