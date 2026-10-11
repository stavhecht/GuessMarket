#!/usr/bin/env bash
# Builds WebClient.jar: the React app (vite build) packed together with the small Java server
# in server/src that serves it and forwards /api to the Guess Market server. The jar needs
# only a JDK (17+) to run; Node.js is needed here, to build it, and nowhere else.
#
# Output, flat, like the other deliverables under out/artifacts, and the whole of the ex4
# submission once README4.pdf is beside it:
#   ../out/artifacts/WebClient_jar/WebClient.jar, GuessMarket.war, run.bat, run.sh
# GuessMarket.war is copied from the Server:war artifact, so build that in IntelliJ first.
set -e
cd "$(dirname "$0")"
OUT=../out/artifacts/WebClient_jar
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

npm install --no-audit --no-fund
npx tsc --noEmit
npx vite build --outDir "$WORK/classes/webclient/static" --emptyOutDir
javac --release 17 -d "$WORK/classes" server/src/webclient/*.java

mkdir -p "$OUT"
jar --create --file "$OUT/WebClient.jar" --main-class webclient.WebClientServer -C "$WORK/classes" .
cp server/run.bat server/run.sh "$OUT/"
if [ -f ../out/artifacts/Server_war/GuessMarket.war ]; then
    cp ../out/artifacts/Server_war/GuessMarket.war "$OUT/"
else
    echo "WARNING: ../out/artifacts/Server_war/GuessMarket.war is missing: build Server:war in IntelliJ."
fi
chmod +x "$OUT/run.sh"
echo
echo "Built $OUT/WebClient.jar"
