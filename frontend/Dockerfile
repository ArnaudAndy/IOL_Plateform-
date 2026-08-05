FROM node:20.19-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
ARG VITE_AUTH_MODE=keycloak
ARG VITE_KEYCLOAK_URL=/auth
ARG VITE_KEYCLOAK_REALM=iol
ARG VITE_KEYCLOAK_CLIENT_ID=iol-web
ENV VITE_AUTH_MODE=${VITE_AUTH_MODE} \
    VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL} \
    VITE_KEYCLOAK_REALM=${VITE_KEYCLOAK_REALM} \
    VITE_KEYCLOAK_CLIENT_ID=${VITE_KEYCLOAK_CLIENT_ID}
RUN npm run build

FROM nginx:1.29-alpine
COPY --from=build /app/dist/ /usr/share/nginx/html/
RUN find /usr/share/nginx/html -type d -exec chmod 0555 {} \; \
    && find /usr/share/nginx/html -type f -exec chmod 0444 {} \;
