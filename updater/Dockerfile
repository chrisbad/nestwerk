# Updater für Nestwerk: führt Updates aus, die ein Admin in der App beauftragt (Docker-Socket, keine Ports).
# Gebaut aus dem Repo-Wurzelverzeichnis: Das Image bringt die Installationsdateien seiner Version mit, damit der
# Updater sie nicht von der (weniger vertrauenswürdigen) App übernehmen muss.
#   docker build -f updater/Dockerfile .
FROM docker:28-cli
LABEL org.opencontainers.image.source="https://github.com/chrisbad/nestwerk" \
      org.opencontainers.image.title="Nestwerk-Updater" \
      org.opencontainers.image.description="Spielt neue Nestwerk-Versionen mit Sicherung und Rückfall ein"
RUN apk add --no-cache bash jq coreutils
COPY compose.prod.yaml compose.proxy.yaml update.sh /nestwerk/
COPY updater/updater.sh /usr/local/bin/updater.sh
RUN chmod +x /usr/local/bin/updater.sh /nestwerk/update.sh
ENTRYPOINT ["/usr/local/bin/updater.sh"]
