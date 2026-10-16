# Updater für Nestwerk: führt Updates aus, die ein Admin in der App beauftragt (Docker-Socket, keine Ports).
FROM docker:28-cli
LABEL org.opencontainers.image.source="https://github.com/chrisbad/nestwerk" \
      org.opencontainers.image.title="Nestwerk-Updater" \
      org.opencontainers.image.description="Spielt neue Nestwerk-Versionen mit Sicherung und Rückfall ein"
RUN apk add --no-cache bash jq coreutils
COPY updater.sh /usr/local/bin/updater.sh
RUN chmod +x /usr/local/bin/updater.sh
ENTRYPOINT ["/usr/local/bin/updater.sh"]
