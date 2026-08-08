@echo off
rem
rem Builds and runs the Guess-Market console app (Engine + UI) on Windows.
rem
rem Run it from anywhere - C:\path\to\GuessMarket\run.bat works from any drive or
rem folder. Everything it needs is resolved from the script's own location (%~dp0).
rem
rem It deliberately does NOT change your working directory: a relative path you type
rem into the app (e.g. XMLexamples\src\single.xml) still resolves against the folder
rem you launched from, exactly as you would expect.

setlocal enabledelayedexpansion

set "MAIN_CLASS=ui.Main"

rem --- where is the project? %~dp0 is this script's folder, with a trailing backslash ---
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "CLASSES_DIR=%PROJECT_DIR%\out\classes"
set "LIB_DIR=%PROJECT_DIR%\lib"

rem --- options ---
set "BUILD=1"
set "APP_ARGS="

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="-h" goto show_usage
if /i "%~1"=="--help" goto show_usage
if /i "%~1"=="/?" goto show_usage
if /i "%~1"=="-n" goto do_no_build
if /i "%~1"=="--no-build" goto do_no_build
if /i "%~1"=="-c" goto do_clean
if /i "%~1"=="--clean" goto do_clean
set "APP_ARGS=%APP_ARGS% %1"
shift
goto parse_args

:do_no_build
set "BUILD=0"
shift
goto parse_args

:do_clean
if exist "%PROJECT_DIR%\out" rmdir /s /q "%PROJECT_DIR%\out"
shift
goto parse_args

:show_usage
echo Usage: run.bat [options] [app arguments]
echo.
echo Options:
echo   -n, --no-build   Run the classes already in out\classes, skip compiling.
echo   -c, --clean      Delete out\ before building.
echo   -h, --help       Show this message.
echo.
echo Examples:
echo   run.bat                  compile, then start the app
echo   run.bat --no-build       start straight away (fastest, after a first run)
echo   run.bat --clean          throw away previous output and rebuild
exit /b 0

:args_done

rem --- which java? JAVA_HOME wins, otherwise whatever is on the PATH ---
if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    if not exist "!JAVA!" (
        echo run.bat: JAVA_HOME is set to %JAVA_HOME%, but !JAVA! is not there. 1>&2
        exit /b 1
    )
) else (
    set "JAVA=java"
    set "JAVAC=javac"
    where java >nul 2>&1
    if errorlevel 1 (
        echo run.bat: no java found. Install a JDK ^(17 or newer^) or set JAVA_HOME. 1>&2
        exit /b 1
    )
)

if not exist "%PROJECT_DIR%\Engine\src" (
    echo run.bat: Engine\src is missing - expected it next to this script, in %PROJECT_DIR%. 1>&2
    exit /b 1
)
if not exist "%PROJECT_DIR%\UI\src" (
    echo run.bat: UI\src is missing - expected it next to this script, in %PROJECT_DIR%. 1>&2
    exit /b 1
)
if not exist "%LIB_DIR%" (
    echo run.bat: lib\ is missing - the JAXB jars are expected in %LIB_DIR%. 1>&2
    exit /b 1
)

if "%BUILD%"=="1" goto build
goto run

:build
if defined JAVA_HOME (
    if not exist "!JAVAC!" (
        echo run.bat: JAVA_HOME points at a JRE - !JAVAC! is missing. Install a JDK, or use --no-build. 1>&2
        exit /b 1
    )
) else (
    where javac >nul 2>&1
    if errorlevel 1 (
        echo run.bat: no javac found ^(a JRE is not enough^). Install a JDK, or use --no-build. 1>&2
        exit /b 1
    )
)

rem One quoted path per line, fed to javac as an @argfile, so long file lists and
rem spaces in the project path are both safe. The paths are written with forward
rem slashes - javac accepts them on Windows, and they keep backslashes from being
rem read as escape characters inside the argfile.
set "SOURCES_FILE=%TEMP%\guessmarket-sources-%RANDOM%.txt"
break > "%SOURCES_FILE%"
for /r "%PROJECT_DIR%\Engine\src" %%f in (*.java) do (
    set "SOURCE_PATH=%%f"
    echo "!SOURCE_PATH:\=/!" >> "%SOURCES_FILE%"
)
for /r "%PROJECT_DIR%\UI\src" %%f in (*.java) do (
    set "SOURCE_PATH=%%f"
    echo "!SOURCE_PATH:\=/!" >> "%SOURCES_FILE%"
)

echo Compiling... 1>&2
if not exist "%CLASSES_DIR%" mkdir "%CLASSES_DIR%"
"%JAVAC%" -encoding UTF-8 -d "%CLASSES_DIR%" -cp "%LIB_DIR%\*" "@%SOURCES_FILE%"
set "COMPILE_STATUS=%errorlevel%"
del "%SOURCES_FILE%" >nul 2>&1
if not "%COMPILE_STATUS%"=="0" exit /b %COMPILE_STATUS%

:run
if not exist "%CLASSES_DIR%" (
    echo run.bat: nothing compiled yet - run without --no-build first. 1>&2
    exit /b 1
)

rem The app prints a few non-ASCII characters, so switch the console to UTF-8 for the
rem run and put the previous code page back afterwards. `chcp` reports it as
rem "Active code page: 437." - hence the trailing period and spaces being stripped.
set "OLD_CODEPAGE="
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CODEPAGE=%%c"
set "OLD_CODEPAGE=%OLD_CODEPAGE: =%"
if "%OLD_CODEPAGE:~-1%"=="." set "OLD_CODEPAGE=%OLD_CODEPAGE:~0,-1%"
chcp 65001 >nul

"%JAVA%" -cp "%CLASSES_DIR%;%LIB_DIR%\*" %MAIN_CLASS%%APP_ARGS%
set "APP_STATUS=%errorlevel%"

if defined OLD_CODEPAGE chcp %OLD_CODEPAGE% >nul
exit /b %APP_STATUS%
