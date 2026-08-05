FROM alpine:3.21
RUN apk add --no-cache stunnel
USER 65534:65534
ENTRYPOINT ["stunnel", "/etc/stunnel/stunnel.conf"]
