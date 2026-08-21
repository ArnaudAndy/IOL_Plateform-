#!/bin/sh
set -eu

cat > "${PGDATA}/pg_hba.conf" <<'EOF'
# Local startup and maintenance stay on the Unix socket inside the container.
local   all             all                                     trust
# Every network client must present a certificate signed by the IOL CA and a
# valid SCRAM password. Service certificate CNs intentionally identify the
# workload (api-core, pipeline-consumer, keycloak), while database roles remain
# distinct credentials; verify-ca is therefore the appropriate mTLS mode.
hostssl all             all             0.0.0.0/0               scram-sha-256 clientcert=verify-ca
hostssl all             all             ::/0                    scram-sha-256 clientcert=verify-ca
hostnossl all           all             0.0.0.0/0               reject
hostnossl all           all             ::/0                    reject
EOF

chmod 0600 "${PGDATA}/pg_hba.conf"
