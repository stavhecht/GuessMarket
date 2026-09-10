#!/usr/bin/env bash
# Launches the app. Everything it needs is named in UI.jar's manifest Class-Path: the JAXB
# jars beside it and JavaFX under javafx/. Nothing has to be installed but a JDK, and
# `java -jar UI.jar` on its own does the same thing from inside the app folder.
#
# The first line is only about finding UI.jar: this script runs both from the project root,
# where the app is under out/artifacts/UI_jar, and from inside that folder, which the
# artifact copies it into. The working directory is deliberately left alone, so a relative
# path typed into the app resolves against wherever you launched it from.
D="$(dirname "$0")"; [ -f "$D/UI.jar" ] || D="$D/out/artifacts/UI_jar"
exec java -jar "$D/UI.jar" "$@"