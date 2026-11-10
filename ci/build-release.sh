#!/usr/bin/env bash
# Release firmada. VERSION_CODE lo pone la librería (nº de commits); el versionName es el del pubspec.yaml.
# Gradle recoge la firma de SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD.
set -euo pipefail
: "${VERSION_CODE:?VERSION_CODE no definido}"
NAME="$(sed -n 's/^version: *\([0-9][0-9.]*\).*/\1/p' pubspec.yaml | head -n1)"
: "${NAME:?no se pudo leer la versión de pubspec.yaml}"
bash ci/api-keys.sh
flutter pub get
if [ -d test ]; then flutter test; fi
flutter build apk --release --build-name="$NAME" --build-number="$VERSION_CODE"
