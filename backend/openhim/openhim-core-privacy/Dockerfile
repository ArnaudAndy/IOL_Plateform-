FROM jembi/openhim-core:v8.5.0

LABEL org.opencontainers.image.title="IOL OpenHIM Core privacy patch" \
      org.opencontainers.image.description="Applies channel body-retention policy to OpenHIM route orchestrations" \
      org.opencontainers.image.version="8.5.0-iol-privacy.1"

COPY apply-orchestration-privacy-patch.js /tmp/apply-orchestration-privacy-patch.js
COPY secure-entrypoint.sh /usr/local/bin/iol-openhim-entrypoint

# The patcher checks the exact upstream source before changing it. A future
# OpenHIM image whose implementation differs will fail the build instead of
# silently producing an unprotected image.
RUN node /tmp/apply-orchestration-privacy-patch.js \
    && rm /tmp/apply-orchestration-privacy-patch.js \
    && chmod 0555 /usr/local/bin/iol-openhim-entrypoint

ENTRYPOINT ["/usr/local/bin/iol-openhim-entrypoint"]
CMD ["node", "lib/server.js"]
