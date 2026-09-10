@echo off
rem Launches the app. Everything it needs is named in UI.jar's manifest Class-Path: the JAXB
rem jars beside it and JavaFX under javafx\. Nothing has to be installed but a JDK, and
rem `java -jar UI.jar` on its own does the same thing from inside the app folder.
rem
rem The two set lines are only about finding UI.jar: this script runs both from the project
rem root, where the app is under out\artifacts\UI_jar, and from inside that folder, which the
rem artifact copies it into. The working directory is deliberately left alone, so a relative
rem path typed into the app resolves against wherever you launched it from.
chcp 65001 >nul
set "D=%~dp0"
if not exist "%D%UI.jar" set "D=%~dp0out\artifacts\UI_jar\"
java -jar "%D%UI.jar" %*