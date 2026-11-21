# front — build multi-étapes : Angular construit par Node, servi par nginx non-root (docs/cadrage.md §1.4.14).
# Les tests unitaires tournent avant, dans la CI (front.yml) ; ce build part du même package-lock.json.
FROM node:22.23-alpine3.24 AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG GIT_SHA=inconnu
RUN npx ng build \
 && printf '{"service":"front","version":"%s"}\n' "$GIT_SHA" > dist/front/browser/version.json

# Image nginx-unprivileged épinglée : UID 101, écoute sur 8080, temporaires dans /tmp.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24
ARG GIT_SHA=inconnu
LABEL org.opencontainers.image.source="https://github.com/mouad-elqaouasse/petite-maison-epouvante" \
      org.opencontainers.image.revision="${GIT_SHA}"
# Correctifs Alpine publiés après l'image de base (Trivy, SQ-03) ; curl inutilisé, retiré avec libcurl.
USER 0
RUN apk upgrade --no-cache \
 && apk del --no-cache curl
# nginx 1.30.7 (CVE-2026-113425, -121810, -123007, -123633 critiques ; -118899, -123634 hautes) : pas encore d'image
# nginx-unprivileged 1.30.7 le 21/11/2026. Paquets du dépôt nginx.org (même build que l'image, branche stable, clé
# déjà présente dans /etc/apk/keys), épinglés comme dans l'image. À RETIRER dès que l'image
# nginxinc/nginx-unprivileged:1.30.7-alpine3.24 sort : remplacer la ligne FROM et supprimer ce RUN.
RUN apk add --no-cache --repository https://nginx.org/packages/alpine/v3.24/main \
      "nginx=1.30.7-r1" "nginx-module-geoip=1.30.7-r1" "nginx-module-image-filter=1.30.7-r1" \
      "nginx-module-xslt=1.30.7-r1" "nginx-module-njs~1.30.7"
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/entetes-securite.conf /etc/nginx/entetes-securite.conf
COPY --from=build /src/dist/front/browser /usr/share/nginx/html
EXPOSE 8080
USER 101:101
