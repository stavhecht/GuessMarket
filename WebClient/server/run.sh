#!/usr/bin/env bash
# Starts Guess Market in the browser: the server if it is not up yet, then the web client,
# which opens http://localhost:3000. Everything it needs sits beside this script:
# GuessMarket.war (the server) and WebClient.jar (the web client). Nothing has to be
# installed but a JDK and a Tomcat 10.
#
# If the market already answers at http://localhost:8080/GuessMarket (or the address given
# as the one argument), Tomcat is left alone. Otherwise the Tomcat in $CATALINA_HOME (or the
# one this project is developed against) is given GuessMarket.war, if its webapps folder does
# not have it yet, and is started and waited for. Tomcat outlives the client, since other
# clients may still be using it: stop it with its own bin/shutdown.sh.
#
# Leave this window open while using the client; Ctrl+C stops the client.
D="$(cd "$(dirname "$0")" && pwd)"; [ -f "$D/WebClient.jar" ] || D="$D/../../out/artifacts/WebClient_jar"
SERVER="${1:-http://localhost:8080/GuessMarket}"
TOMCAT="${CATALINA_HOME:-$HOME/Downloads/apache-tomcat-10.1.59}"

# The market answers 401 to anyone not logged in; a Tomcat still deploying answers 404.
answering() {
    case "$(curl -s -o /dev/null -w '%{http_code}' "$SERVER/api/market")" in
        200|401) return 0 ;;
        *) return 1 ;;
    esac
}

if ! answering; then
    if [ -f "$TOMCAT/bin/startup.sh" ]; then
        if [ ! -e "$TOMCAT/webapps/GuessMarket.war" ] && [ ! -d "$TOMCAT/webapps/GuessMarket" ] && [ -f "$D/GuessMarket.war" ]; then
            echo "Deploying GuessMarket.war to $TOMCAT/webapps ..."
            cp "$D/GuessMarket.war" "$TOMCAT/webapps/"
        fi
        echo "Starting Tomcat in $TOMCAT ..."
        sh "$TOMCAT/bin/startup.sh" >/dev/null
        for _ in $(seq 1 90); do answering && break; sleep 1; done
    else
        echo "No Tomcat found: set CATALINA_HOME to your Tomcat 10 folder and run this again,"
        echo "or start Tomcat with GuessMarket.war deployed yourself. Starting the client anyway."
    fi
fi

exec java -jar "$D/WebClient.jar" "$@"
