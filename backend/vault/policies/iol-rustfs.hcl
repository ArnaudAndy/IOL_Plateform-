path "transit/encrypt/iol-rustfs" {
  capabilities = ["update"]
}

path "transit/decrypt/iol-rustfs" {
  capabilities = ["update"]
}

path "transit/keys/iol-rustfs" {
  capabilities = ["read"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}

path "auth/token/renew-self" {
  capabilities = ["update"]
}
