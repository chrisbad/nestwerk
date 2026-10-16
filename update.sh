#!/usr/bin/env bash
# shellcheck disable=SC1111 # deutsche Anführungszeichen in Meldungen sind Absicht
# Nestwerk aktualisieren: erst mit der laufenden Version sichern, dann das neue Image laden und neu starten.
#   ./update.sh           auf die in .env eingestellte Version (Standard: latest)
#   ./update.sh 1.4.0     auf eine bestimmte Version wechseln (wird in .env gespeichert)
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || { echo "Keine .env gefunden – bitte im Installationsverzeichnis ausführen." >&2; exit 1; }

if [ -n "${1:-}" ]; then
    sed -i "s/^NESTWERK_VERSION=.*/NESTWERK_VERSION=$1/" .env
    echo "Version in .env auf $1 gesetzt."
fi

echo "Sicherung vor dem Update (mit der bisherigen Version) …"
if ! docker compose run --rm --no-deps -T app \
        --nestwerk.backup.run-and-exit=true --server.port=0; then
    read -r -p "Die Sicherung ist fehlgeschlagen. Trotzdem aktualisieren? [j/N] " answer </dev/tty || answer=n
    case "$answer" in j|J|ja|y|Y) ;; *) echo "Abgebrochen."; exit 1 ;; esac
fi

echo "Neues Image laden und neu starten …"
docker compose pull app
docker compose up -d --wait
docker image prune -f >/dev/null
echo "Fertig. Die Sicherung von eben liegt in Nestwerk unter „Sicherungen“ (Art: vor einem Update)."
