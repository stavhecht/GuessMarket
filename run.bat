@echo off
rem Runs the built UI.jar. Its dependency jars are found through the jar's manifest
rem Class-Path, so they only need to sit next to it. The working directory is left
rem alone, so a relative path typed into the app resolves against wherever you are.
chcp 65001 >nul
java -jar "%~dp0out\artifacts\UI_jar\UI.jar" %*