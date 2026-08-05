#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${IOL_BASE_URL:?IOL_BASE_URL est obligatoire}"

docker_host_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*) cygpath -w "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1 ;;
esac

args=(
  --rm
  -e "IOL_BASE_URL=${IOL_BASE_URL}"
  -e "IOL_LOAD_VUS=${IOL_LOAD_VUS:-10}"
  -e "IOL_LOAD_DURATION=${IOL_LOAD_DURATION:-30s}"
  -e "IOL_INSECURE_TLS=${IOL_INSECURE_TLS:-false}"
  -v "$(docker_host_path "${SCRIPT_DIR}"):/scripts:ro"
)

if [[ -n "${IOL_ACCESS_TOKEN_FILE:-}" ]]; then
  if [[ ! -s "${IOL_ACCESS_TOKEN_FILE}" ]]; then
    printf 'Token de test absent ou vide: %s\n' "${IOL_ACCESS_TOKEN_FILE}" >&2
    exit 1
  fi
  IOL_ACCESS_TOKEN="$(tr -d '\r\n' < "${IOL_ACCESS_TOKEN_FILE}")"
  args+=(-e "IOL_ACCESS_TOKEN=${IOL_ACCESS_TOKEN}")
fi

if [[ "${IOL_INSECURE_TLS:-false}" == "true" ]]; then
  printf 'ATTENTION: verification TLS desactivee pour ce test local uniquement.\n' >&2
fi

docker run "${args[@]}" grafana/k6:2.0.0 run /scripts/load-smoke.js
