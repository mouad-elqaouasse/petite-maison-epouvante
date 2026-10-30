# troc-service — image d'exécution.
# Le jar est construit et testé avant (./mvnw verify) : l'image contient exactement le jar testé (docs/cadrage.md §1.3.12).
# Image distroless : ni shell ni gestionnaire de paquets, utilisateur nonroot (UID 65532).
FROM gcr.io/distroless/java25-debian13:nonroot

ARG GIT_SHA=inconnu
ENV GIT_SHA=${GIT_SHA} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

LABEL org.opencontainers.image.source="https://github.com/mouad-elqaouasse/petite-maison-epouvante" \
      org.opencontainers.image.revision="${GIT_SHA}"

COPY target/app.jar /app/app.jar
# 8080 : API ; 8081 : actuator (sondes et métriques, ADR 0007).
EXPOSE 8080 8081
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
