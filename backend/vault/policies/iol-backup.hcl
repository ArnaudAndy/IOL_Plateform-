# Autorise uniquement la lecture d'un snapshot Raft. Cette identite ne peut ni
# lire un secret logique, ni dechiffrer un credential Transit.
path "sys/storage/raft/snapshot" {
  capabilities = ["read"]
}

path "sys/storage/raft/configuration" {
  capabilities = ["read"]
}

path "sys/health" {
  capabilities = ["read"]
}
