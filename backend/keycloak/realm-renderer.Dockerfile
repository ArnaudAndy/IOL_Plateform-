FROM alpine:3.21
RUN apk add --no-cache bash jq
COPY render-realm.sh /usr/local/bin/render-iol-realm
RUN chmod 0555 /usr/local/bin/render-iol-realm
ENTRYPOINT ["/usr/local/bin/render-iol-realm"]
