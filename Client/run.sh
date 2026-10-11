#!/usr/bin/env bash
# Launches the client. Everything it needs is named in Client.jar's manifest Class-Path: the
# jars beside it. Nothing has to be installed but a JDK, and `java -jar Client.jar` on its
# own does the same thing from inside the client folder.
#
# The server is found by itself: http://localhost:8080/GuessMarket, which is where Tomcat
# deploys GuessMarket.war. A different address can be given as the one argument.
#
# If nothing answers there, the Tomcat in $CATALINA_HOME (or the one this project is developed
# against) is started first and waited for. One that already answers is left alone, so a
# second client, or a Tomcat run from IntelliJ, never starts another. Tomcat outlives the
# client: other clients may still be using it. Stop it with its own bin/shutdown.sh.
#
# The first line is only about finding Client.jar: this script runs both from the Client
# module, where the app is under ../out/artifacts/Client_jar, and from inside that folder,
# which the artifact copies it into.
D="$(dirname "$0")"; [ -f "$D/Client.jar" ] || D="$D/../out/artifacts/Client_jar"
SERVER="${1:-http://localhost:8080/GuessMarket}"
TOMCAT="${CATALINA_HOME:-$HOME/Downloads/apache-tomcat-10.1.59}"

# The market answers 401 to anyone not logged in; a Tomcat still deploying answers 404.
answering() {
    case "$(curl -s -o /dev/null -w '%{http_code}' "$SERVER/api/market")" in
        200|401) return 0 ;;
        *) return 1 ;;
    esac
}

if ! answering && [ -x "$TOMCAT/bin/startup.sh" ]; then
    echo "Starting Tomcat in $TOMCAT ..."
    "$TOMCAT/bin/startup.sh" >/dev/null
    for _ in $(seq 1 60); do answering && break; sleep 1; done
fi

exec java -jar "$D/Client.jar" "$@"