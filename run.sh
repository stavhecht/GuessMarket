#!/usr/bin/env bash
# Runs the built UI.jar. Its dependency jars are found through the jar's manifest
# Class-Path, so they only need to sit next to it. The working directory is left
# alone, so a relative path typed into the app resolves against wherever you are.
exec java -jar "$(dirname "$0")/out/artifacts/UI_jar/UI.jar" "$@"