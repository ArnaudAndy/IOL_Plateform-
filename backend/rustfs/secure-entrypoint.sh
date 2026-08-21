#!/bin/sh
set -eu

read_required_secret() {
  variable_name="$1"
  file_path="$2"
  if [ ! -r "$file_path" ]; then
    printf 'Secret RustFS illisible: %s\n' "$file_path" >&2
    exit 1
  fi
  value="$(cat "$file_path")"
  export "$variable_name=$value"
}

read_required_secret RUSTFS_ACCESS_KEY "${RUSTFS_ACCESS_KEY_FILE:-/run/secrets/rustfs-root-access-key}"
read_required_secret RUSTFS_SECRET_KEY "${RUSTFS_SECRET_KEY_FILE:-/run/secrets/rustfs-root-secret-key}"
read_required_secret RUSTFS_KMS_VAULT_TOKEN "${RUSTFS_KMS_VAULT_TOKEN_FILE:-/run/secrets/rustfs-vault-token}"

# RustFS interdit qu'une meme option soit fournie a la fois directement et
# via sa variante *_FILE. Les secrets viennent deja d'etre lus ci-dessus.
unset RUSTFS_ACCESS_KEY_FILE RUSTFS_SECRET_KEY_FILE RUSTFS_KMS_VAULT_TOKEN_FILE

exec /usr/bin/rustfs server "$@"
