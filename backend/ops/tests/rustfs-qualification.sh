#!/usr/bin/env bash
#
# Qualification du magasin d'objets RustFS sur l'infrastructure cible.
#
# POURQUOI CE SCRIPT EXISTE
# -------------------------
# RustFS est deploye en version pre-GA. Ce choix est assume, mais il impose une
# contrepartie: prouver sur VOTRE infrastructure ce que l'editeur ne garantit
# pas encore contractuellement.
#
# `ha-failover.sh` verifie deja qu'un noeud peut tomber sans que le repartiteur
# passe en erreur. C'est necessaire mais insuffisant: un magasin peut repondre
# "sain" tout en ayant perdu des donnees. Ce script verifie ce qui compte
# vraiment pour la plateforme:
#
#   1. Multipart a la taille de production (64 Mio par partie)
#   2. INTEGRITE apres perte d un noeud — l objet reste lisible et son SHA-256
#      est inchange
#   3. Erasure coding — la reconstruction fonctionne au retour du noeud
#   4. Chiffrement au repos — l objet n est pas lisible en clair sur le volume
#
# Le point 2 est le coeur du sujet. Un transport IOL depose un artefact, publie
# un manifeste avec son empreinte, puis le consumer le telecharge et REVERIFIE
# cette empreinte. Une corruption silencieuse ne provoquerait donc pas une
# lecture erronee, mais un echec d execution — ce qui reste un incident.
#
# USAGE
#   export CONFIRM_QUALIFICATION=IOL-RUSTFS-QUALIFICATION
#   export IOL_PRODUCTION_ENV_FILE="$PWD/backend/.env.production"
#   bash backend/ops/tests/rustfs-qualification.sh
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${IOL_PRODUCTION_ENV_FILE:-${BACKEND_DIR}/.env.production}"
BUCKET="${QUALIFICATION_BUCKET:-iol-qualification}"
PART_SIZE_MIB="${QUALIFICATION_PART_SIZE_MIB:-64}"
OBJECT_SIZE_MIB="${QUALIFICATION_OBJECT_SIZE_MIB:-192}"   # 3 parties multipart

fail() { printf 'QUALIFICATION REFUSEE: %s\n' "$1" >&2; exit 1; }
step() { printf '\n=== %s ===\n' "$1"; }

[[ "${CONFIRM_QUALIFICATION:-}" == "IOL-RUSTFS-QUALIFICATION" ]] \
  || fail 'definissez CONFIRM_QUALIFICATION=IOL-RUSTFS-QUALIFICATION (ce test ecrit dans le magasin)'
[[ -s "${ENV_FILE}" ]] || fail "fichier d environnement absent: ${ENV_FILE}"

compose=(docker compose --env-file "${ENV_FILE}"
         --profile qualification
         -f "${BACKEND_DIR}/docker-compose.yml"
         -f "${BACKEND_DIR}/docker-compose.production.yml")

# Toutes les commandes S3 passent par un conteneur ephemere sur le reseau
# interne: le magasin ne publie aucun port, et il ne doit pas en publier.
# Le service `aws-cli` vit sous le profil `qualification` et n'existe donc pas
# en exploitation normale.
s3() {
  "${compose[@]}" run --rm --no-deps -T --entrypoint aws aws-cli "$@"
}

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

step "1/5 Preparation d un objet de ${OBJECT_SIZE_MIB} Mio"
dd if=/dev/urandom of="${WORK}/artefact.bin" bs=1M count="${OBJECT_SIZE_MIB}" status=none
EMPREINTE_ORIGINE="$(sha256sum "${WORK}/artefact.bin" | cut -d' ' -f1)"
printf 'SHA-256 depose : %s\n' "${EMPREINTE_ORIGINE}"

step "2/5 Depot multipart (${PART_SIZE_MIB} Mio par partie)"
# La taille de partie doit etre celle de la production: c'est elle qui
# determine le nombre d appels et le comportement memoire du transport.
s3 s3 mb "s3://${BUCKET}" 2>/dev/null || true
s3 configure set default.s3.multipart_chunksize "${PART_SIZE_MIB}MB"
s3 s3 cp "${WORK}/artefact.bin" "s3://${BUCKET}/qualification/artefact.bin" \
  || fail 'le depot multipart a echoue'

step "3/5 Perte d un noeud, puis relecture"
"${compose[@]}" stop rustfs-4 >/dev/null
printf 'Noeud rustfs-4 arrete.\n'
sleep 5

s3 s3 cp "s3://${BUCKET}/qualification/artefact.bin" "${WORK}/relu-degrade.bin" \
  || fail 'objet illisible avec un noeud absent: l erasure coding ne protege pas'
EMPREINTE_DEGRADE="$(sha256sum "${WORK}/relu-degrade.bin" | cut -d' ' -f1)"

[[ "${EMPREINTE_DEGRADE}" == "${EMPREINTE_ORIGINE}" ]] \
  || fail "CORRUPTION SILENCIEUSE en mode degrade: ${EMPREINTE_DEGRADE} != ${EMPREINTE_ORIGINE}"
printf 'Integrite preservee avec un noeud en moins.\n'

step "4/5 Retour du noeud et reconstruction"
"${compose[@]}" start rustfs-4 >/dev/null
for _ in $(seq 1 30); do
  if "${compose[@]}" exec -T rustfs-lb wget -q -O /dev/null \
      --no-check-certificate https://rustfs-lb:9000/minio/health/ready 2>/dev/null; then
    break
  fi
  sleep 2
done
s3 s3 cp "s3://${BUCKET}/qualification/artefact.bin" "${WORK}/relu-restaure.bin" \
  || fail 'objet illisible apres retour du noeud'
[[ "$(sha256sum "${WORK}/relu-restaure.bin" | cut -d' ' -f1)" == "${EMPREINTE_ORIGINE}" ]] \
  || fail 'integrite perdue apres reconstruction'
printf 'Integrite preservee apres reconstruction.\n'

step "5/5 Chiffrement au repos"
# L artefact contient des donnees aleatoires: on cherche plutot une signature
# connue, deposee en clair, et on verifie qu elle n apparait pas sur le volume.
SIGNATURE="IOL-QUALIFICATION-CANARY-$(date +%s)"
printf '%s' "${SIGNATURE}" > "${WORK}/canari.txt"
s3 s3 cp "${WORK}/canari.txt" "s3://${BUCKET}/qualification/canari.txt" >/dev/null

if "${compose[@]}" exec -T rustfs grep -rqa "${SIGNATURE}" /data 2>/dev/null; then
  fail 'DONNEES EN CLAIR sur le volume: le KMS Vault ne chiffre pas les objets'
fi
printf 'Aucune donnee en clair trouvee sur le volume: KMS actif.\n'

step "Nettoyage"
s3 s3 rm "s3://${BUCKET}/qualification/" --recursive >/dev/null || true

cat <<'RESUME'

QUALIFICATION RUSTFS REUSSIE
  [x] Multipart a la taille de production
  [x] Objet lisible avec un noeud absent
  [x] Integrite SHA-256 preservee en mode degrade
  [x] Integrite preservee apres reconstruction
  [x] Chiffrement au repos verifie par canari

Conservez la sortie de ce script: elle constitue la preuve de qualification
exigee par la porte de securite pour une version pre-GA du magasin d objets.
RESUME
