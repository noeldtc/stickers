#!/usr/bin/env bash
# Validación de PR: dependencias, análisis estático (solo errores rompen) y APK debug.
set -euo pipefail
flutter --version
flutter pub get
flutter analyze --no-fatal-infos --no-fatal-warnings
if [ -d test ]; then flutter test; fi
flutter build apk --debug
