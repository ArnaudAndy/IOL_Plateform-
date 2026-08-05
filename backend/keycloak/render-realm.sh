#!/usr/bin/env bash
set -Eeuo pipefail

: "${IOL_PUBLIC_URL:?IOL_PUBLIC_URL is required, for example https://iol.example.org}"
SOURCE_FILE="${1:-/config/iol-realm.json}"
TARGET_FILE="${2:-/import/iol-realm.json}"

case "${IOL_PUBLIC_URL}" in
  https://*) ;;
  *) printf 'IOL_PUBLIC_URL must use https:// in production.\n' >&2; exit 1 ;;
esac

jq --arg publicUrl "${IOL_PUBLIC_URL%/}" '
  (.clients[] | select(.clientId == "iol-web") | .redirectUris) = [$publicUrl + "/*"] |
  (.clients[] | select(.clientId == "iol-web") | .webOrigins) = [$publicUrl] |
  (.clients[] | select(.clientId == "iol-web") | .attributes["post.logout.redirect.uris"]) = ($publicUrl + "/*") |
  (.clients[] | select(.clientId == "openhim-console") | .redirectUris) = [$publicUrl + "/openhim-console/*"] |
  (.clients[] | select(.clientId == "openhim-console") | .webOrigins) = [$publicUrl] |
  .attributes.frontendUrl = ($publicUrl + "/auth")
' "${SOURCE_FILE}" > "${TARGET_FILE}"

jq -e '.realm == "iol" and (.clients | length >= 6)' "${TARGET_FILE}" >/dev/null
chmod 644 "${TARGET_FILE}"
