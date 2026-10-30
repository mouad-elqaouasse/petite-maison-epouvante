# front — build multi-étapes : Angular construit par Node, servi par nginx non-root (docs/cadrage.md §1.4.14).
# Les tests unitaires tournent avant, dans la CI (front.yml) ; ce build part du même package-lock.json.
FROM node:22.22-alpine3.24 AS build
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
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/entetes-securite.conf /etc/nginx/entetes-securite.conf
COPY --from=build /src/dist/front/browser /usr/share/nginx/html
EXPOSE 8080
USER 101:101
