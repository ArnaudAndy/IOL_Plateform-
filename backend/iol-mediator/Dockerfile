FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY src ./src
COPY docker-entrypoint.sh /usr/local/bin/iol-mediator-entrypoint
RUN chmod 0555 /usr/local/bin/iol-mediator-entrypoint

ENV NODE_ENV=production
ENV PORT=3000

EXPOSE 3000

ENTRYPOINT ["/usr/local/bin/iol-mediator-entrypoint"]
