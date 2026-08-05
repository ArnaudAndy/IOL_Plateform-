ui = false
disable_mlock = true
cluster_name = "iol-vault-production"
api_addr = "https://__NODE_NAME__:8200"
cluster_addr = "https://__NODE_NAME__:8201"

storage "raft" {
  path = "/vault/data"
  node_id = "__NODE_NAME__"

  retry_join {
    leader_api_addr = "https://vault-1:8200"
    leader_ca_cert_file = "/vault/tls/ca.pem"
    leader_client_cert_file = "/vault/tls/vault.crt"
    leader_client_key_file = "/vault/tls/vault.key"
  }
  retry_join {
    leader_api_addr = "https://vault-2:8200"
    leader_ca_cert_file = "/vault/tls/ca.pem"
    leader_client_cert_file = "/vault/tls/vault.crt"
    leader_client_key_file = "/vault/tls/vault.key"
  }
  retry_join {
    leader_api_addr = "https://vault-3:8200"
    leader_ca_cert_file = "/vault/tls/ca.pem"
    leader_client_cert_file = "/vault/tls/vault.crt"
    leader_client_key_file = "/vault/tls/vault.key"
  }
}

listener "tcp" {
  address = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_cert_file = "/vault/tls/vault.crt"
  tls_key_file = "/vault/tls/vault.key"
  tls_client_ca_file = "/vault/tls/ca.pem"
  tls_require_and_verify_client_cert = true
  tls_min_version = "tls13"
}

# RustFS currently authenticates its Vault KMS client with a narrowly scoped
# periodic token, but cannot present a client certificate. This second listener
# is reachable only through the dedicated internal vault-client network.
listener "tcp" {
  address = "0.0.0.0:8202"
  tls_cert_file = "/vault/tls/vault.crt"
  tls_key_file = "/vault/tls/vault.key"
  tls_min_version = "tls13"
}

telemetry {
  disable_hostname = true
  prometheus_retention_time = "30s"
}

__SEAL_CONFIG__
