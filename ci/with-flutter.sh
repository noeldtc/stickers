#!/usr/bin/env bash
# Ejecuta un comando con el SDK de Flutter fijado en el PATH, instalándolo la primera vez.
# Uso: bash ci/with-flutter.sh <comando> [args...]
set -euo pipefail

FLUTTER_VERSION="${FLUTTER_VERSION:-3.35.7}"
ROOT="${FLUTTER_TOOLS_DIR:-${JENKINS_HOME:-$HOME}/tools}"
DIR="$ROOT/flutter-$FLUTTER_VERSION"

if [ ! -x "$DIR/bin/flutter" ]; then
    mkdir -p "$ROOT"
    (
        # Una sola instalación a la vez si hay builds en paralelo
        command -v flock >/dev/null && flock 9
        if [ ! -x "$DIR/bin/flutter" ]; then
            TMP="$(mktemp -d "$ROOT/.flutter-install-XXXXXX")"
            trap 'rm -rf "$TMP"' EXIT
            echo "Instalando Flutter $FLUTTER_VERSION en $DIR"
            git clone --quiet --depth 1 --branch "$FLUTTER_VERSION" -c advice.detachedHead=false \
                https://github.com/flutter/flutter.git "$TMP/flutter"
            mv "$TMP/flutter" "$DIR"
        fi
    ) 9>"$ROOT/.flutter-install.lock"
fi

export PATH="$DIR/bin:$PATH"
export CI=true FLUTTER_SUPPRESS_ANALYTICS=true
exec "$@"
