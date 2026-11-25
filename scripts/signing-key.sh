#!/usr/bin/env bash
# Готовит постоянный ключ подписи APK для CI.
#  1. Если задан секрет NOVA_KEYSTORE_BASE64 (или старое имя LOLI_KEYSTORE_BASE64) — используется он.
#  2. Иначе — ключ из кэша Actions (.signing), созданный при первой сборке.
#  3. Если кэша нет — создаётся новый ключ со случайным паролем; workflow сохранит его в кэш.
# Отладочный ключ Android (CN=Android Debug) не принимается: его пароль «android» известен всем,
# и подделку с такой подписью Android поставит поверх настоящей Нова.
# Пароли маскируются в логах и передаются дальше только через GITHUB_ENV.
set -euo pipefail
DIR=".signing"
mkdir -p "$DIR"
KEYSTORE="$DIR/nova-release.jks"
ALIAS="nova"

# Владелец сертификата — чтобы отличить отладочный ключ от настоящего.
cert_dn() {
  keytool -list -v -keystore "$1" -storepass "$2" -alias "$3" 2>/dev/null | sed -nE 's/^Owner: //p' | head -1
}

B64="${NOVA_KEYSTORE_BASE64:-${LOLI_KEYSTORE_BASE64:-}}"
if [ -n "$B64" ]; then
  STORE_PASS="${NOVA_KEYSTORE_PASSWORD_SECRET:-${LOLI_KEYSTORE_PASSWORD_SECRET:-}}"
  KEY_ALIAS="${NOVA_KEY_ALIAS_SECRET:-${LOLI_KEY_ALIAS_SECRET:-}}"
  KEY_PASS="${NOVA_KEY_PASSWORD_SECRET:-${LOLI_KEY_PASSWORD_SECRET:-$STORE_PASS}}"
  OUT="$RUNNER_TEMP/nova-release.jks"
  printf '%s' "$B64" | base64 -d > "$OUT"
  DN="$(cert_dn "$OUT" "$STORE_PASS" "$KEY_ALIAS")"
  if [ -z "$DN" ]; then
    echo "::error::Секрет подписи не читается: проверьте NOVA_KEYSTORE_BASE64, пароль и alias (сейчас «$KEY_ALIAS»)."
    exit 1
  fi
  case "$DN" in
    *"CN=Android Debug"*)
      echo "::error::В секретах лежит ОТЛАДОЧНЫЙ ключ Android ($DN): его пароль «android» известен всем."
      echo "::error::Загрузите настоящий ключ в NOVA_KEYSTORE_BASE64 — как это сделать, см. docs/SIGNING.md."
      exit 1
      ;;
  esac
  echo "::add-mask::$STORE_PASS"
  {
    echo "NOVA_KEYSTORE_FILE=$OUT"
    echo "NOVA_KEYSTORE_PASSWORD=$STORE_PASS"
    echo "NOVA_KEY_ALIAS=$KEY_ALIAS"
    echo "NOVA_KEY_PASSWORD=$KEY_PASS"
  } >> "$GITHUB_ENV"
  echo "Подпись ключом из секретов репозитория ($DN)"
  exit 0
fi

if [ ! -f "$KEYSTORE" ] || [ ! -f "$DIR/password" ]; then
  # Новый ключ = другая подпись: установленная Нова перестанет обновляться поверх. Молча этого не делаем.
  if [ "${NOVA_ALLOW_NEW_KEY:-${LOLI_ALLOW_NEW_KEY:-}}" != "1" ]; then
    echo "::error::Ключ подписи не найден (кэш Actions стёрт, секретов нет). Новый ключ сломает обновления у всех установок."
    echo "::error::Задайте секреты NOVA_KEYSTORE_BASE64 / NOVA_KEYSTORE_PASSWORD / NOVA_KEY_ALIAS / NOVA_KEY_PASSWORD"
    echo "::error::(docs/SIGNING.md) или запустите релиз с new_key=true, если перевыпуск ключа осознанный."
    exit 1
  fi
  echo "Создаю постоянный ключ подписи (хранится в кэше Actions)"
  PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
  rm -f "$KEYSTORE"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=Nova Assistant, O=Nova" >/dev/null 2>&1
  printf '%s' "$PASS" > "$DIR/password"
else
  echo "Ключ подписи восстановлен из кэша"
fi

PASS="$(cat "$DIR/password")"
echo "::add-mask::$PASS"
{
  echo "NOVA_KEYSTORE_FILE=$PWD/$KEYSTORE"
  echo "NOVA_KEYSTORE_PASSWORD=$PASS"
  echo "NOVA_KEY_ALIAS=$ALIAS"
  echo "NOVA_KEY_PASSWORD=$PASS"
} >> "$GITHUB_ENV"
