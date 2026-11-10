#!/usr/bin/env bash
# lib/src/api_keys.dart está en .gitignore (guarda la clave de la API de Google Fonts) pero el código lo importa:
# sin él ni el análisis ni la compilación funcionan. Si no existe lo genera con FONTS_API_KEY, o vacío.
set -euo pipefail
F=lib/src/api_keys.dart
if [ -f "$F" ]; then exit 0; fi
KEY="${FONTS_API_KEY:-}"
case "$KEY" in *[\'\"\\$]*) echo "FONTS_API_KEY contiene caracteres no válidos"; exit 1 ;; esac
printf "const String fontsKey = '%s';\n" "$KEY" > "$F"
[ -n "$KEY" ] || echo "⚠️ Sin FONTS_API_KEY: el catálogo de Google Fonts no funcionará en esta build"
