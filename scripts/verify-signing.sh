#!/usr/bin/env bash
# Проверяет, что APK подписан официальным ключом, а не отладочным.
# Отпечаток берётся из docs/SIGNING.md — единственного источника правды: если он там не указан,
# релиз не публикуется (иначе можно молча выпустить сборку с чужой подписью).
# Использование: ./scripts/verify-signing.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail

APK="${1:?укажите путь к APK}"
DOC="$(dirname "$0")/../docs/SIGNING.md"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "::error::Не найден Android SDK (ANDROID_HOME): без apksigner подпись не проверить."
  exit 1
fi
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
if [ -z "$APKSIGNER" ]; then
  echo "::error::apksigner не найден в $SDK/build-tools — установите Android build-tools."
  exit 1
fi

EXPECTED="$(
  sed -nE 's/^\*\*Отпечаток официального ключа \(SHA-256\):\*\*[[:space:]]*`?([0-9A-Fa-f:]{95})`?.*/\1/p' "$DOC" | head -1
)"
if [ -z "$EXPECTED" ]; then
  echo "::error::В docs/SIGNING.md нет отпечатка официального ключа — релиз подписывать нечем сверять."
  exit 1
fi

CERT="$("$APKSIGNER" verify --print-certs "$APK" | sed -nE 's/^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$/\1/p' | head -1 | tr 'a-f' 'A-F')"
DN="$("$APKSIGNER" verify --print-certs "$APK" | sed -nE 's/^Signer #1 certificate DN: //p' | head -1)"
if [ -z "$CERT" ]; then
  echo "::error::Не удалось прочитать сертификат подписи из $APK."
  exit 1
fi
ACTUAL="$(printf '%s' "$CERT" | fold -w2 | paste -sd: -)"

echo "Сертификат: $DN"
echo "SHA-256:    $ACTUAL"

case "$DN" in
  *"CN=Android Debug"*)
    echo "::error::APK подписан отладочным ключом Android (CN=Android Debug). Такой ключ есть у всех — публиковать нельзя."
    echo "::error::Задайте секреты подписи (docs/SIGNING.md) или перевыпустите ключ запуском Release APK с new_key=true."
    exit 1
    ;;
esac

if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "::error::Подпись не совпадает с официальной из docs/SIGNING.md (ожидалось $EXPECTED)."
  exit 1
fi

echo "Подпись соответствует официальному ключу."
