path "transit/encrypt/iol-business-credentials" {
  capabilities = ["update"]
}

path "transit/decrypt/iol-business-credentials" {
  capabilities = ["update"]
}

path "transit/rewrap/iol-business-credentials" {
  capabilities = ["update"]
}

path "transit/keys/iol-business-credentials" {
  capabilities = ["read"]
}

path "sys/health" {
  capabilities = ["read"]
}
