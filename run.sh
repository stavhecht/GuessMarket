#!/usr/bin/env bash
# Runs the built UI.jar. Its JAXB dependency jars are found through the jar's manifest
# Class-Path, so they only need to sit next to it. The working directory is left
# alone, so a relative path typed into the app resolves against wherever you are.
#
# JavaFX is not part of the JDK and cannot travel in the manifest, so the desktop UI needs
# the SDK named on the module path. Point JAVAFX_HOME at yours if it lives elsewhere.
JAVAFX_HOME="${JAVAFX_HOME:-$HOME/Documents/javafx-sdk-25.0.4}"

if [ ! -d "$JAVAFX_HOME/lib" ]; then
  echo "JavaFX SDK not found at $JAVAFX_HOME." >&2
  echo "Download it from https://openjfx.io and set JAVAFX_HOME to where you put it." >&2
  exit 1
fi

exec java --module-path "$JAVAFX_HOME/lib" --add-modules javafx.controls \
          --enable-native-access=javafx.graphics \
          -jar "$(dirname "$0")/out/artifacts/UI_jar/UI.jar" "$@"
