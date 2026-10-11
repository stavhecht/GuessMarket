@echo off
rem Starts the Guess Market web client: installs its packages (npm install), then serves the
rem React app at http://localhost:3000 and opens it in the browser.
rem
rem It does nothing to the server. Tomcat is started and deployed exactly as for the JavaFX
rem client (GuessMarket.war in webapps, so the market is at http://localhost:8080/GuessMarket);
rem this only connects to it. A different server address can be given as the one argument:
rem     run.bat http://otherhost:8080/GuessMarket
rem
rem Leave this window open while using the client; closing it (or Ctrl+C) stops the client.
rem Needs Node.js 18 or newer, and the internet the first time, for npm install.
chcp 65001 >nul
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
    echo Node.js was not found. Install it from https://nodejs.org and run this again.
    pause
    exit /b 1
)
node -e "process.exit(+process.versions.node.split('.')[0] >= 18 ? 0 : 1)"
if errorlevel 1 (
    echo This needs Node.js 18 or newer; this machine has:
    node --version
    pause
    exit /b 1
)

set "GM_SERVER=http://localhost:8080/GuessMarket"
if not "%~1"=="" set "GM_SERVER=%~1"

rem The market answers 401 to anyone not logged in; anything else means it is not up yet.
node -e "fetch(process.argv[1] + '/api/market').then(r => process.exit(r.status === 401 || r.status === 200 ? 0 : 1), () => process.exit(1))" "%GM_SERVER%"
if errorlevel 1 (
    echo.
    echo WARNING: the Guess Market server does not answer at %GM_SERVER%
    echo Start Tomcat with GuessMarket deployed. The client starts anyway and connects once it is up.
    echo.
)

echo Installing the client's packages (the first run takes a minute)...
call npm install --no-audit --no-fund
if errorlevel 1 (
    echo npm install failed; see the messages above.
    pause
    exit /b 1
)

echo.
echo Guess Market web client: http://localhost:3000   (server: %GM_SERVER%)
echo.
call npm start
