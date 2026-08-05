#!/bin/sh
set -eu

ROOT_PASSWORD="$(cat /run/secrets/mongodb-root-password)"
APP_PASSWORD="$(cat /run/secrets/mongodb-app-password)"
OPENHIM_PASSWORD="$(cat /run/secrets/mongodb-openhim-password)"
MONGO_ARGS="--tls --tlsCAFile /run/tls/ca.pem --tlsCertificateKeyFile /run/tls/mongodb.pem --host mongodb --port 27017"

until mongosh ${MONGO_ARGS} --quiet --eval 'db.adminCommand({ ping: 1 }).ok' 2>/dev/null \
    | grep -qx 1; do
  sleep 2
done

if ! mongosh ${MONGO_ARGS} --quiet \
    --username "${MONGO_INITDB_ROOT_USERNAME}" --password "${ROOT_PASSWORD}" \
    --authenticationDatabase admin --eval 'rs.status().ok' 2>/dev/null | grep -qx 1; then
  mongosh ${MONGO_ARGS} \
    --username "${MONGO_INITDB_ROOT_USERNAME}" --password "${ROOT_PASSWORD}" \
    --authenticationDatabase admin --eval '
      rs.initiate({
        _id: "rs-iol",
        members: [
          { _id: 0, host: "mongodb:27017", priority: 2 },
          { _id: 1, host: "mongodb-2:27017", priority: 1 },
          { _id: 2, host: "mongodb-3:27017", priority: 1 }
        ]
      })'
fi

until mongosh ${MONGO_ARGS} --quiet \
    --username "${MONGO_INITDB_ROOT_USERNAME}" --password "${ROOT_PASSWORD}" \
    --authenticationDatabase admin --eval 'db.hello().isWritablePrimary' 2>/dev/null \
    | grep -qx true; do
  sleep 2
done

mongosh ${MONGO_ARGS} \
  --username "${MONGO_INITDB_ROOT_USERNAME}" --password "${ROOT_PASSWORD}" \
  --authenticationDatabase admin --eval "
    const appDb = db.getSiblingDB('iol_metadata');
    if (!appDb.getUser('${MONGODB_APP_USERNAME}')) {
      appDb.createUser({
        user: '${MONGODB_APP_USERNAME}',
        pwd: '${APP_PASSWORD}',
        roles: [{ role: 'readWrite', db: 'iol_metadata' }]
      });
    }
  "

mongosh ${MONGO_ARGS} \
  --username "${MONGO_INITDB_ROOT_USERNAME}" --password "${ROOT_PASSWORD}" \
  --authenticationDatabase admin --eval "
    const openhimDb = db.getSiblingDB('openhim');
    if (!openhimDb.getUser('${OPENHIM_MONGODB_USERNAME}')) {
      openhimDb.createUser({
        user: '${OPENHIM_MONGODB_USERNAME}',
        pwd: '${OPENHIM_PASSWORD}',
        roles: [{ role: 'readWrite', db: 'openhim' }]
      });
    }
  "

printf 'Replica set MongoDB initialise et compte applicatif present.\n'
