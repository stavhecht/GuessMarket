#!/usr/bin/env bash
# The same as run.bat, for macOS and Linux: npm install, then serve the web client at
# http://localhost:3000 against the server at http://localhost:8080/GuessMarket (or the
# address given as the one argument). It does nothing to the server.
set -e
cd "$(dirname "$0")"

command -v node >/dev/null || { echo "Node.js was not found. Install it from https://nodejs.org"; exit 1; }
node -e "process.exit(+process.versions.node.split('.')[0] >= 18 ? 0 : 1)" \
    || { echo "This needs Node.js 18 or newer; this machine has $(node --version)."; exit 1; }

export GM_SERVER="${1:-http://localhost:8080/GuessMarket}"

node -e "fetch(process.argv[1] + '/api/market').then(r => process.exit(r.status === 401 || r.status === 200 ? 0 : 1), () => process.exit(1))" "$GM_SERVER" \
    || printf '\nWARNING: the Guess Market server does not answer at %s\nStart Tomcat with GuessMarket deployed. The client starts anyway and connects once it is up.\n\n' "$GM_SERVER"

echo "Installing the client's packages (the first run takes a minute)..."
npm install --no-audit --no-fund

printf '\nGuess Market web client: http://localhost:3000   (server: %s)\n\n' "$GM_SERVER"
exec npm start
