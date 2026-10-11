@echo off
rem Starts Guess Market in the browser: the server if it is not up yet, then the web client,
rem which opens http://localhost:3000. Everything it needs sits beside this script:
rem GuessMarket.war (the server) and WebClient.jar (the web client). Nothing has to be
rem installed but a JDK and a Tomcat 10, named by CATALINA_HOME.
rem
rem If the market already answers at http://localhost:8080/GuessMarket (or the address given
rem as the one argument), Tomcat is left alone. Otherwise the Tomcat in CATALINA_HOME is given
rem GuessMarket.war, if its webapps folder does not have it yet, and is started (in its own
rem window, as startup.bat always does) and waited for. Tomcat outlives the client, since
rem other clients may still be using it: stop it with its own bin\shutdown.bat.
rem
rem Leave this window open while using the client; closing it (or Ctrl+C) stops the client.
chcp 65001 >nul
setlocal
set "D=%~dp0"
if not exist "%D%WebClient.jar" set "D=%~dp0..\..\out\artifacts\WebClient_jar\"
set "SERVER=http://localhost:8080/GuessMarket"
if not "%~1"=="" set "SERVER=%~1"

call :answering && goto launch
if not defined CATALINA_HOME goto notomcat
if not exist "%CATALINA_HOME%\bin\startup.bat" goto notomcat
if exist "%CATALINA_HOME%\webapps\GuessMarket.war" goto start
if exist "%CATALINA_HOME%\webapps\GuessMarket\" goto start
if not exist "%D%GuessMarket.war" goto start
echo Deploying GuessMarket.war to %CATALINA_HOME%\webapps ...
copy /y "%D%GuessMarket.war" "%CATALINA_HOME%\webapps\" >nul
:start
echo Starting Tomcat in %CATALINA_HOME% ...
call "%CATALINA_HOME%\bin\startup.bat" >nul
set /a TRIES=0
:wait
call :answering && goto launch
set /a TRIES+=1
if %TRIES% geq 90 goto launch
timeout /t 1 /nobreak >nul
goto wait

:notomcat
echo No Tomcat found: set CATALINA_HOME to your Tomcat 10 folder and run this again,
echo or start Tomcat with GuessMarket.war deployed yourself. Starting the client anyway.

:launch
java -jar "%D%WebClient.jar" %*
if errorlevel 1 pause
exit /b

rem The market answers 401 to anyone not logged in; a Tomcat still deploying answers 404.
:answering
set "CODE="
for /f %%c in ('curl -s -o NUL -w "%%{http_code}" "%SERVER%/api/market" 2^>nul') do set "CODE=%%c"
if "%CODE%"=="401" exit /b 0
if "%CODE%"=="200" exit /b 0
exit /b 1
