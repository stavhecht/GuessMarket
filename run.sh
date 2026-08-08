#!/usr/bin/env bash
#
# Builds and runs the Guess-Market console app (Engine + UI) on macOS and Linux.
#
# Run it from anywhere — /path/to/GuessMarket/run.sh, ./run.sh, or through a symlink
# on your PATH. Everything it needs is resolved from the script's own location.
#
# It deliberately does NOT change your working directory: a relative path you type
# into the app (e.g. XMLexamples/src/single.xml) still resolves against the directory
# you launched from, exactly as you would expect.

set -euo pipefail

MAIN_CLASS="ui.Main"
SOURCE_DIRS=("Engine/src" "UI/src")

usage() {
    cat <<'USAGE'
Usage: run.sh [options] [-- app arguments]

Options:
  -n, --no-build   Run the classes already in out/classes, skip compiling.
  -c, --clean      Delete out/ before building.
  -h, --help       Show this message.

Examples:
  ./run.sh                       compile, then start the app
  ./run.sh --no-build            start straight away (fastest, after a first run)
  ./run.sh --clean               throw away previous output and rebuild
USAGE
}

die() {
    echo "run.sh: $*" >&2
    exit 1
}

# --- where is the project? (follow symlinks, so a link on your PATH works too) ---
script_path="${BASH_SOURCE[0]}"
while [ -L "$script_path" ]; do
    link_dir="$(cd -P "$(dirname "$script_path")" && pwd)"
    script_path="$(readlink "$script_path")"
    case "$script_path" in
        /*) ;;                                  # already absolute
        *) script_path="$link_dir/$script_path" ;;
    esac
done
PROJECT_DIR="$(cd -P "$(dirname "$script_path")" && pwd)"

CLASSES_DIR="$PROJECT_DIR/out/classes"
LIB_DIR="$PROJECT_DIR/lib"

# --- options ---
build=true
app_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        -n|--no-build) build=false; shift ;;
        -c|--clean) rm -rf "$PROJECT_DIR/out"; shift ;;
        --) shift; app_args+=("$@"); break ;;
        *) app_args+=("$1"); shift ;;
    esac
done

# --- which java? JAVA_HOME wins, otherwise whatever is on the PATH ---
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
else
    JAVA="java"
    JAVAC="javac"
fi
command -v "$JAVA" >/dev/null 2>&1 || die "no java found. Install a JDK (17 or newer) or set JAVA_HOME."

for dir in "${SOURCE_DIRS[@]}"; do
    [ -d "$PROJECT_DIR/$dir" ] || die "$dir is missing — expected it next to this script, in $PROJECT_DIR."
done
[ -d "$LIB_DIR" ] || die "lib/ is missing — the JAXB jars are expected in $LIB_DIR."

# --- build ---
if [ "$build" = true ]; then
    command -v "$JAVAC" >/dev/null 2>&1 || die "no javac found (a JRE is not enough). Install a JDK, or use --no-build."

    sources_file="$(mktemp "${TMPDIR:-/tmp}/guessmarket-sources.XXXXXX")"
    trap 'rm -f "$sources_file"' EXIT

    # One quoted path per line, fed to javac as an @argfile so long file lists and
    # spaces in the project path both stay safe.
    find "${SOURCE_DIRS[@]/#/$PROJECT_DIR/}" -name '*.java' -print \
        | sed 's/^/"/; s/$/"/' > "$sources_file"
    [ -s "$sources_file" ] || die "found no .java files under ${SOURCE_DIRS[*]}."

    echo "Compiling..." >&2
    mkdir -p "$CLASSES_DIR"
    "$JAVAC" -encoding UTF-8 -d "$CLASSES_DIR" -cp "$LIB_DIR/*" "@$sources_file"
fi

[ -d "$CLASSES_DIR" ] || die "nothing compiled yet — run without --no-build first."

# --- run (exec, so Ctrl-C reaches the app and its exit code is ours) ---
exec "$JAVA" -cp "$CLASSES_DIR:$LIB_DIR/*" "$MAIN_CLASS" ${app_args[@]+"${app_args[@]}"}