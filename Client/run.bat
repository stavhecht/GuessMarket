@echo off
rem Launches the client. Everything it needs is named in Client.jar's manifest Class-Path: the
rem jars beside it. Nothing has to be installed but a JDK, and `java -jar Client.jar` on its
rem own does the same thing from inside the client folder.
rem
rem The server is found by itself: http://localhost:8080/GuessMarket, which is where Tomcat
rem deploys GuessMarket.war. A different address can be given as the one argument.
rem
rem If nothing answers there and CATALINA_HOME names a Tomcat, that Tomcat is started first
rem (in its own window, as startup.bat always does) and waited for. One that already answers
rem is left alone, so a second client never starts another. Tomcat outlives the client:
rem other clients may still be using it. Stop it with its own bin\shutdown.bat.
rem
rem The two set lines are only about finding Client.jar: this script runs both from the
rem Client module, where the app is under ..\out\artifacts\Client_jar, and from inside that
rem folder, which the artifact copies it into.
chcp 65001 >nul
setlocal
set "D=%~dp0"
if not exist "%D%Client.jar" set "D=%~dp0..\out\artifacts\Client_jar\"
set "SERVER=http://localhost:8080/GuessMarket"
if not "%~1"=="" set "SERVER=%~1"

call :answering && goto launch
if not defined CATALINA_HOME goto launch
if not exist "%CATALINA_HOME%\bin\startup.bat" goto launch
echo Starting Tomcat in %CATALINA_HOME% ...
call "%CATALINA_HOME%\bin\startup.bat" >nul
set /a TRIES=0
:wait
call :answering && goto launch
set /a TRIES+=1
if %TRIES% geq 60 goto launch
timeout /t 1 /nobreak >nul
goto wait

:launch
java -jar "%D%Client.jar" %*
exit /b

rem The market answers 401 to anyone not logged in; a Tomcat still deploying answers 404.
:answering
set "CODE="
for /f %%c in ('curl -s -o NUL -w "%%{http_code}" "%SERVER%/api/market" 2^>nul') do set "CODE=%%c"
if "%CODE%"=="401" exit /b 0
if "%CODE%"=="200" exit /b 0
exit /b 1