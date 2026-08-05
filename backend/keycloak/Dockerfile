FROM quay.io/keycloak/keycloak:26.7.0 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY start-keycloak.sh /opt/keycloak/bin/start-iol-keycloak.sh
COPY bootstrap-keycloak.sh /opt/keycloak/bin/bootstrap-iol-keycloak.sh
USER root
RUN chmod 0555 /opt/keycloak/bin/start-iol-keycloak.sh /opt/keycloak/bin/bootstrap-iol-keycloak.sh
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/start-iol-keycloak.sh"]
