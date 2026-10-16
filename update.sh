#!/usr/bin/env bash
# shellcheck disable=SC1111 # deutsche Anführungszeichen in Meldungen sind Absicht
# Nestwerk per SSH aktualisieren. Normalerweise geht das in Nestwerk unter „Updates“; dieses Skript braucht man für
# die erste Einrichtung des Updaters oder wenn die App nicht mehr startet.
# Ablauf: mit der laufenden Version sichern, Installationsdateien und Image der Zielversion holen, neu starten.
#   ./update.sh           auf die in .env eingestellte Version (Standard: latest)
#   ./update.sh 1.4.0     auf eine bestimmte Version wechseln (wird in .env gespeichert)
# Bei privatem Repository GITHUB_TOKEN setzen, sonst bleiben die Installationsdateien unverändert.
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || { echo "Keine .env gefunden – bitte im Installationsverzeichnis ausführen." >&2; exit 1; }
REPO="chrisbad/nestwerk"
FILES=(compose.prod.yaml compose.proxy.yaml update.sh)

env_set() {
    if grep -qE "^$1=" .env; then
        sed -i "s|^$1=.*|$1=$2|" .env
    else
        printf '%s=%s\n' "$1" "$2" >>.env
    fi
}

if [ -n "${1:-}" ]; then
    env_set NESTWERK_VERSION "$1"
    echo "Version in .env auf $1 gesetzt."
fi
if ! grep -q '^NESTWERK_DIR=' .env; then
    env_set NESTWERK_DIR "$(pwd)"
    echo "NESTWERK_DIR=$(pwd) in .env ergänzt (für den Updater)."
fi

version="$(grep -E '^NESTWERK_VERSION=' .env | tail -1 | cut -d= -f2-)"
case "${version:-latest}" in
    latest) ref="main" ;;
    sha-*) ref="${version#sha-}" ;;
    *) ref="v$version" ;;
esac

echo "Installationsdateien der Version ${version:-latest} laden …"
tmp="$(mktemp -d ./.update.XXXXXX)"
trap 'rm -rf "$tmp"' EXIT
auth=()
[ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: token $GITHUB_TOKEN")
loaded=1
for f in "${FILES[@]}"; do
    if ! curl -fsSL ${auth[@]+"${auth[@]}"} "https://raw.githubusercontent.com/$REPO/$ref/$f" -o "$tmp/$f"; then
        loaded=0
        break
    fi
done
if [ "$loaded" = 1 ]; then
    chmod +x "$tmp/update.sh"
    # mv statt cp: dieses Skript läuft gerade und darf nicht überschrieben, nur ersetzt werden
    for f in "${FILES[@]}"; do
        mv -f "$tmp/$f" "$f"
    done
else
    echo "Konnte die Dateien nicht laden (privates Repository? GITHUB_TOKEN setzen) – die vorhandenen bleiben." >&2
fi

echo "Sicherung vor dem Update (mit der bisherigen Version) …"
if ! docker compose run --rm --no-deps -T app \
        --nestwerk.backup.run-and-exit=true --server.port=0; then
    read -r -p "Die Sicherung ist fehlgeschlagen. Trotzdem aktualisieren? [j/N] " answer </dev/tty || answer=n
    case "$answer" in j|J|ja|y|Y) ;; *) echo "Abgebrochen."; exit 1 ;; esac
fi

echo "Neue Images laden und neu starten …"
docker compose pull
docker compose up -d --wait
docker image prune -f >/dev/null
echo "Fertig. Die Sicherung von eben liegt in Nestwerk unter „Sicherungen“ (Art: vor einem Update)."
