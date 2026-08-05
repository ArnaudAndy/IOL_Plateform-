#!/bin/sh
set -eu

cat > "${PGDATA}/pg_hba.conf" <<'EOF'
# Local startup and maintenance stay on the Unix socket inside the container.
local   all             all                                     trust
# Every network client must present a certificate signed by the IOL CA and a password.
hostssl all             all             0.0.0.0/0               scram-sha-256 clientcert=verify-full
hostssl all             all             ::/0                    scram-sha-256 clientcert=verify-full
hostnossl all           all             0.0.0.0/0               reject
hostnossl all           all             ::/0                    reject
EOF

chmod 0600 "${PGDATA}/pg_hba.conf"
