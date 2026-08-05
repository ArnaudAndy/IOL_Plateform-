#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
SECRETS_DIR="${IOL_SECRETS_DIR:-${BACKEND_DIR}/secrets}"
TLS_DIR="${SECRETS_DIR}/tls"
CA_PRIVATE_DIR="${SECRETS_DIR}/pki-ca-private"
RUNTIME_DIR="${SECRETS_DIR}/runtime-tls"
CA_DAYS="${IOL_CA_DAYS:-3650}"
LEAF_DAYS="${IOL_LEAF_DAYS:-397}"
PUBLIC_HOSTNAME="${IOL_PUBLIC_HOSTNAME:-iol.example.com}"

for command_name in openssl keytool; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    printf 'Commande requise absente: %s\n' "${command_name}" >&2
    exit 1
  }
done

if [[ -e "${CA_PRIVATE_DIR}/ca.key" && "${FORCE:-false}" != "true" ]]; then
  printf 'PKI deja presente dans %s. Utilisez FORCE=true uniquement pour une rotation planifiee.\n' \
    "${TLS_DIR}" >&2
  exit 1
fi

mkdir -p "${TLS_DIR}" "${CA_PRIVATE_DIR}" "${RUNTIME_DIR}"
chmod 700 "${SECRETS_DIR}" "${TLS_DIR}" "${CA_PRIVATE_DIR}" "${RUNTIME_DIR}"

STORE_PASSWORD_FILE="${SECRETS_DIR}/tls-store-password"
if [[ ! -s "${STORE_PASSWORD_FILE}" ]]; then
  openssl rand -base64 36 | tr -d '\n' > "${STORE_PASSWORD_FILE}"
  chmod 600 "${STORE_PASSWORD_FILE}"
fi
STORE_PASSWORD="$(cat "${STORE_PASSWORD_FILE}")"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "${CA_PRIVATE_DIR}/ca.key"
chmod 600 "${CA_PRIVATE_DIR}/ca.key"
openssl req -x509 -new -sha384 -days "${CA_DAYS}" \
  -key "${CA_PRIVATE_DIR}/ca.key" \
  -subj '/C=CM/O=IOL/OU=Platform Security/CN=IOL Preproduction Root CA' \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:1' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "${TLS_DIR}/ca.pem"
cp "${TLS_DIR}/ca.pem" "${TLS_DIR}/ca.crt"

issue_certificate() {
  local name="$1"
  shift
  local san=''
  local dns_name
  for dns_name in "$@"; do
    if [[ -n "${san}" ]]; then san+=','; fi
    san+="DNS:${dns_name}"
  done

  local config_file="${TLS_DIR}/${name}.openssl.cnf"
  cat > "${config_file}" <<EOF
[req]
prompt = no
distinguished_name = dn
req_extensions = req_ext
[dn]
C = CM
O = IOL
OU = Platform Runtime
CN = ${name}
[req_ext]
subjectAltName = ${san}
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth,clientAuth
[cert_ext]
subjectAltName = ${san}
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth,clientAuth
EOF

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "${TLS_DIR}/${name}.key"
  openssl req -new -key "${TLS_DIR}/${name}.key" -config "${config_file}" \
    -out "${TLS_DIR}/${name}.csr"
  openssl x509 -req -sha384 -days "${LEAF_DAYS}" \
    -in "${TLS_DIR}/${name}.csr" \
    -CA "${TLS_DIR}/ca.pem" -CAkey "${CA_PRIVATE_DIR}/ca.key" -CAcreateserial \
    -extfile "${config_file}" -extensions cert_ext \
    -out "${TLS_DIR}/${name}.crt"
  cat "${TLS_DIR}/${name}.key" "${TLS_DIR}/${name}.crt" > "${TLS_DIR}/${name}.pem"
  openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER \
    -in "${TLS_DIR}/${name}.key" -out "${TLS_DIR}/${name}.pk8"
  openssl pkcs12 -export -name "${name}" \
    -inkey "${TLS_DIR}/${name}.key" -in "${TLS_DIR}/${name}.crt" \
    -certfile "${TLS_DIR}/ca.pem" -passout "pass:${STORE_PASSWORD}" \
    -out "${TLS_DIR}/${name}.p12"
  chmod 600 "${TLS_DIR}/${name}.key" "${TLS_DIR}/${name}.pem" \
    "${TLS_DIR}/${name}.pk8" "${TLS_DIR}/${name}.p12"
  rm -f "${TLS_DIR}/${name}.csr" "${config_file}"
}

issue_certificate nginx nginx localhost "${PUBLIC_HOSTNAME}"
issue_certificate api-core api-core
issue_certificate pipeline-consumer pipeline-consumer
issue_certificate iol-mediator iol-mediator
issue_certificate iol-fhir-mediator iol-fhir-mediator
issue_certificate iol-iso20022-mediator iol-iso20022-mediator
issue_certificate iol-edfi-mediator iol-edfi-mediator
issue_certificate openhim openhim
issue_certificate postgres postgres
issue_certificate mongodb mongodb
issue_certificate mongodb-2 mongodb-2
issue_certificate mongodb-3 mongodb-3
issue_certificate kafka kafka
issue_certificate kafka-2 kafka-2
issue_certificate kafka-3 kafka-3
issue_certificate kafka-admin kafka-admin
issue_certificate rustfs rustfs
issue_certificate rustfs-2 rustfs-2 rustfs
issue_certificate rustfs-3 rustfs-3 rustfs
issue_certificate rustfs-4 rustfs-4 rustfs
issue_certificate rustfs-lb rustfs-lb
issue_certificate keycloak keycloak keycloak-1 keycloak-2 "${PUBLIC_HOSTNAME}"
issue_certificate clamav clamav
issue_certificate spark-master spark-master
issue_certificate spark-worker spark-worker
issue_certificate vault vault vault-1 vault-2 vault-3
issue_certificate vault-renewer vault-renewer

rm -f "${TLS_DIR}/truststore.p12"
keytool -importcert -noprompt -alias iol-preprod-root \
  -file "${TLS_DIR}/ca.pem" -keystore "${TLS_DIR}/truststore.p12" \
  -storetype PKCS12 -storepass "${STORE_PASSWORD}" >/dev/null
chmod 600 "${TLS_DIR}/truststore.p12"

for credential_file in kafka-keystore-password kafka-key-password kafka-truststore-password; do
  printf '%s' "${STORE_PASSWORD}" > "${SECRETS_DIR}/${credential_file}"
  chmod 600 "${SECRETS_DIR}/${credential_file}"
done

openssl rand -base64 756 | tr -d '\n' > "${SECRETS_DIR}/mongodb-keyfile"
openssl rand -hex 48 > "${SECRETS_DIR}/spark-auth-secret"
chmod 400 "${SECRETS_DIR}/mongodb-keyfile" "${SECRETS_DIR}/spark-auth-secret"

runtime_bundle() {
  local bundle_name="$1"
  local certificate_name="$2"
  local destination="${RUNTIME_DIR}/${bundle_name}"
  mkdir -p "${destination}"
  find "${destination}" -mindepth 1 -maxdepth 1 -type f -delete
  cp "${TLS_DIR}/ca.pem" "${TLS_DIR}/truststore.p12" "${destination}/"
  for extension in crt key pem pk8 p12; do
    cp "${TLS_DIR}/${certificate_name}.${extension}" "${destination}/"
  done
  chmod 755 "${destination}"
  chmod 444 "${destination}"/*
}

runtime_bundle api-core api-core
runtime_bundle pipeline-consumer pipeline-consumer
runtime_bundle nginx nginx
runtime_bundle postgres postgres
runtime_bundle mongodb mongodb
runtime_bundle mongodb-2 mongodb-2
runtime_bundle mongodb-3 mongodb-3
runtime_bundle kafka-1 kafka
runtime_bundle kafka-2 kafka-2
runtime_bundle kafka-3 kafka-3
runtime_bundle kafka-admin kafka-admin
runtime_bundle rustfs rustfs
runtime_bundle rustfs-2 rustfs-2
runtime_bundle rustfs-3 rustfs-3
runtime_bundle rustfs-4 rustfs-4
runtime_bundle rustfs-lb rustfs-lb
runtime_bundle keycloak keycloak
runtime_bundle clamav clamav
runtime_bundle spark-master spark-master
runtime_bundle spark-worker spark-worker
runtime_bundle iol-mediator iol-mediator
runtime_bundle iol-fhir-mediator iol-fhir-mediator
runtime_bundle iol-iso20022-mediator iol-iso20022-mediator
runtime_bundle iol-edfi-mediator iol-edfi-mediator
runtime_bundle openhim openhim
runtime_bundle vault vault
runtime_bundle vault-renewer vault-renewer

for node in rustfs rustfs-2 rustfs-3 rustfs-4; do
  cp "${RUNTIME_DIR}/${node}/${node}.crt" "${RUNTIME_DIR}/${node}/rustfs_cert.pem"
  cp "${RUNTIME_DIR}/${node}/${node}.key" "${RUNTIME_DIR}/${node}/rustfs_key.pem"
  chmod 444 "${RUNTIME_DIR}/${node}/rustfs_cert.pem" "${RUNTIME_DIR}/${node}/rustfs_key.pem"
done

for node in kafka-1 kafka-2 kafka-3 kafka-admin; do
  cp "${SECRETS_DIR}/kafka-keystore-password" "${RUNTIME_DIR}/${node}/"
  cp "${SECRETS_DIR}/kafka-key-password" "${RUNTIME_DIR}/${node}/"
  cp "${SECRETS_DIR}/kafka-truststore-password" "${RUNTIME_DIR}/${node}/"
  chmod 444 "${RUNTIME_DIR}/${node}"/kafka-*-password
done

openssl verify -CAfile "${TLS_DIR}/ca.pem" "${TLS_DIR}"/*.crt
printf 'PKI de preproduction generee dans %s\n' "${TLS_DIR}"
printf 'Production: remplacez cette CA locale par Vault PKI ou votre CA organisationnelle.\n'
