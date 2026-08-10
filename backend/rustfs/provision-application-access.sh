#!/bin/sh
set -eu

read_secret() {
  path="$1"
  label="$2"
  if [ ! -r "$path" ]; then
    printf 'Secret RustFS illisible: %s (%s)\n' "$path" "$label" >&2
    exit 1
  fi
  tr -d '\r\n' < "$path"
}

ENDPOINT="${RUSTFS_ENDPOINT:-https://rustfs-lb:9000}"
BUCKET="${OBJECT_STORAGE_BUCKET:-iol-source-data}"
POLICY_NAME="iol-source-data-v1"
ROOT_ACCESS_KEY="$(read_secret /run/secrets/rustfs-root-access-key root-access-key)"
ROOT_SECRET_KEY="$(read_secret /run/secrets/rustfs-root-secret-key root-secret-key)"
APP_ACCESS_KEY="$(read_secret /run/secrets/rustfs-app-access-key app-access-key)"
APP_SECRET_KEY="$(read_secret /run/secrets/rustfs-app-secret-key app-secret-key)"
MC="mc --config-dir /tmp/mc"

case "$BUCKET" in
  *[!a-z0-9.-]*|'')
    printf 'Nom de bucket RustFS invalide: %s\n' "$BUCKET" >&2
    exit 1
    ;;
esac

$MC alias set iol "$ENDPOINT" "$ROOT_ACCESS_KEY" "$ROOT_SECRET_KEY" --api S3v4 >/dev/null
$MC mb --ignore-existing "iol/$BUCKET" >/dev/null

cat > /tmp/iol-source-data-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetBucketLocation",
        "s3:ListBucket",
        "s3:ListBucketMultipartUploads"
      ],
      "Resource": ["arn:aws:s3:::$BUCKET"]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts"
      ],
      "Resource": ["arn:aws:s3:::$BUCKET/source-data/*"]
    }
  ]
}
EOF

# Le nom versionne rend la politique immutable: une evolution de privileges
# doit creer v2 et passer en revue, pas modifier silencieusement v1 en place.
if ! $MC admin policy info iol "$POLICY_NAME" >/dev/null 2>&1; then
  $MC admin policy create iol "$POLICY_NAME" /tmp/iol-source-data-policy.json >/dev/null
fi

# `user add` est reutilisable: RustFS cree l'identite au premier deploiement et
# reapplique le secret lors d'une rotation avant le demarrage des applications.
$MC admin user add iol "$APP_ACCESS_KEY" "$APP_SECRET_KEY" >/dev/null
$MC admin policy attach iol "$POLICY_NAME" --user "$APP_ACCESS_KEY" >/dev/null

# Verification avec l'identite applicative, pas avec root.
$MC alias set iol-app "$ENDPOINT" "$APP_ACCESS_KEY" "$APP_SECRET_KEY" --api S3v4 >/dev/null
$MC ls "iol-app/$BUCKET" >/dev/null
printf 'Bucket et identite applicative RustFS provisionnes.\n'
