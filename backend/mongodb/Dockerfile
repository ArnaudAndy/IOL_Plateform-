FROM mongo:8.2
COPY secure-entrypoint.sh /usr/local/bin/iol-mongodb-entrypoint
RUN chmod 0555 /usr/local/bin/iol-mongodb-entrypoint
ENTRYPOINT ["/usr/local/bin/iol-mongodb-entrypoint"]
CMD ["mongod"]
