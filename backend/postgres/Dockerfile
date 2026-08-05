FROM postgres:16
COPY secure-entrypoint.sh /usr/local/bin/iol-postgres-entrypoint
RUN chmod 0555 /usr/local/bin/iol-postgres-entrypoint
ENTRYPOINT ["/usr/local/bin/iol-postgres-entrypoint"]
CMD ["postgres"]
