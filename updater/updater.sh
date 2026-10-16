#!/usr/bin/env bash
# shellcheck disable=SC1111 # deutsche Anführungszeichen in Meldungen sind Absicht
# Nestwerk-Updater: arbeitet die Update-Aufträge der App ab (Design: docs/superpowers/specs/2026-10-16-updates-design.md).
#
# Vertrauensgrenze: Die App (Benutzer 1001) darf nur /work/jobs/<id>/ beschreiben und liefert dort request.json und
# „ready“. Daraus übernimmt der Updater ausschließlich den geprüften Image-Tag. Installationsdateien kommen aus dem
# Updater-Image der Zielversion, Status, Protokoll und Kopien schreibt er nach /work/results/<id>/ (nur root).
# Ablauf: Sicherung → Images laden → Dateien tauschen → Neustart; schlägt etwas fehl, zurück auf die alte Version und –
# falls die neue schon migriert hatte – auf die Sicherung von eben.
set -uo pipefail

WORK="${UPDATER_WORK:-/work}"
INSTALL="${NESTWERK_DIR:?NESTWERK_DIR fehlt}"
WAIT="${UPDATER_WAIT_TIMEOUT:-300}"
APP_UID="${APP_UID:-1001}"
FILES=(compose.prod.yaml compose.proxy.yaml update.sh)
ID=""
OUT=""
LOG=/dev/null

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }

# status ZUSTAND SCHRITT MELDUNG – atomar, damit die App nie eine halbe Datei liest
status() {
    jq -n --arg s "$1" --arg step "$2" --arg m "$3" --arg t "$(now)" \
        '{status: $s, step: $step, message: $m, updatedAt: $t}' >"$OUT/status.json.tmp" \
        && mv -f "$OUT/status.json.tmp" "$OUT/status.json"
}

compose() { (cd "$INSTALL" && docker compose "$@") >>"$LOG" 2>&1; }

env_get() { grep -E "^$1=" "$INSTALL/.env" | tail -1 | cut -d= -f2-; }

env_set() {
    if grep -qE "^$1=" "$INSTALL/.env"; then
        sed -i "s|^$1=.*|$1=$2|" "$INSTALL/.env"
    else
        printf '%s=%s\n' "$1" "$2" >>"$INSTALL/.env"
    fi
}

# reguläre Datei der App, kein Symlink, höchstens 64 KB
app_file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %s "$1")" -le 65536 ]; }

# Höchste angewandte Migration; leer, wenn die Datenbank nicht antwortet
flyway_rank() {
    (cd "$INSTALL" && docker compose exec -T db sh -c \
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT coalesce(max(installed_rank), 0) FROM flyway_schema_history"') \
        2>>"$LOG" | tr -d '[:space:]'
}

save_previous() {
    mkdir -p "$OUT/previous"
    cp -p "$INSTALL/.env" "$OUT/previous/.env"
    for f in "${FILES[@]}"; do
        [ -f "$INSTALL/$f" ] && cp -p "$INSTALL/$f" "$OUT/previous/$f"
    done
}

restore_previous() {
    cp -p "$OUT/previous/.env" "$INSTALL/.env"
    for f in "${FILES[@]}"; do
        [ -f "$OUT/previous/$f" ] && cp -p "$OUT/previous/$f" "$INSTALL/$f"
    done
}

# Installationsdateien aus dem Updater-Image der Zielversion nach $OUT/files
extract_files() {
    local image="$1" container
    container="$(docker create "$image" 2>>"$LOG")" || return 1
    mkdir -p "$OUT/files"
    docker cp "$container:/nestwerk/." "$OUT/files" >>"$LOG" 2>&1
    local ok=$?
    docker rm "$container" >/dev/null 2>&1
    [ "$ok" = 0 ] || return 1
    for f in "${FILES[@]}"; do
        [ -s "$OUT/files/$f" ] || return 1
    done
}

# rollback GRUND RANG_VORHER SICHERUNG
rollback() {
    local reason="$1" before="$2" backup="$3" after
    log "Fehler: $reason Zurück auf die vorherige Version."
    status RUNNING ROLLBACK "$reason Zurück auf die vorherige Version …"
    restore_previous
    after="$(flyway_rank)"
    if [ -z "$after" ] || [ "$after" -gt "$before" ]; then
        log "Die neue Version hat die Datenbank verändert (Migration $before → ${after:-?}); Sicherung $backup wird eingespielt."
        compose stop app
        if ! compose run --rm --no-deps -T app "--nestwerk.backup.restore-and-exit=$backup" --server.port=0; then
            status FAILED ROLLBACK "$reason Auch das Zurücksetzen der Daten ist fehlgeschlagen. Bitte per SSH prüfen; die Sicherung von vorher heißt $backup."
            return
        fi
    fi
    if ! compose up -d --wait --wait-timeout "$WAIT" db app; then
        status FAILED ROLLBACK "$reason Auch die vorherige Version startet nicht mehr. Bitte per SSH prüfen (docker compose logs app)."
        return
    fi
    log "Die vorherige Version läuft wieder."
    status ROLLED_BACK ROLLBACK "$reason Die vorherige Version läuft wieder, die Daten sind unverändert."
}

run_job() {
    ID="$1"
    local job="$WORK/jobs/$ID" tag to before backup image
    OUT="$WORK/results/$ID"
    mkdir -p "$OUT"
    LOG="$OUT/log.txt"
    : >"$LOG"
    if ! app_file "$job/request.json"; then
        status FAILED BACKUP "Der Auftrag ist ungültig."
        return
    fi
    tag="$(jq -r '.imageTag // empty' "$job/request.json" 2>/dev/null)"
    to="$(jq -r '.toVersion // empty' "$job/request.json" 2>/dev/null)"
    if ! [[ "$tag" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$ ]] || ! [[ "$to" =~ ^[A-Za-z0-9._-]{1,60}$ ]]; then
        status FAILED BACKUP "Der Auftrag ist ungültig (Version „${tag:0:60}“)."
        return
    fi
    image="$(env_get NESTWERK_IMAGE)"
    image="${image:-ghcr.io/chrisbad/nestwerk}"
    log "Update auf $to (Image-Tag $tag)"

    status RUNNING BACKUP "Sicherung mit der laufenden Version …"
    if ! compose run --rm --no-deps -T app --nestwerk.backup.run-and-exit=true --server.port=0 \
            "--nestwerk.backup.result-file=/data/updater/jobs/$ID/backup"; then
        log "Die Sicherung ist fehlgeschlagen."
        status FAILED BACKUP "Die Sicherung ist fehlgeschlagen; es wurde nichts verändert."
        return
    fi
    backup="$(app_file "$job/backup" && head -c 200 "$job/backup")"
    if ! [[ "$backup" =~ ^nestwerk-[A-Za-z0-9._-]+\.zip$ ]]; then
        status FAILED BACKUP "Die Sicherung meldet keinen gültigen Dateinamen; es wurde nichts verändert."
        return
    fi
    printf '%s' "$backup" >"$OUT/backup"
    log "Gesichert: $backup"
    before="$(flyway_rank)"
    before="${before:-0}"
    save_previous

    status RUNNING PULL "Neue Version wird geladen …"
    if ! docker pull "$image:$tag" >>"$LOG" 2>&1 || ! docker pull "$image-updater:$tag" >>"$LOG" 2>&1 \
            || ! extract_files "$image-updater:$tag"; then
        log "Die neue Version ließ sich nicht laden."
        status FAILED PULL "Die neue Version ließ sich nicht laden; es wurde nichts verändert."
        return
    fi

    status RUNNING FILES "Installationsdateien der neuen Version …"
    for f in "${FILES[@]}"; do
        cp "$OUT/files/$f" "$INSTALL/$f"
    done
    chmod +x "$INSTALL/update.sh"
    env_set NESTWERK_VERSION "$tag"
    [ -n "$(env_get NESTWERK_DIR)" ] || env_set NESTWERK_DIR "$INSTALL"

    status RUNNING START "Neustart mit der neuen Version …"
    if ! compose up -d --wait --wait-timeout "$WAIT" db app; then
        rollback "Die neue Version ist nicht gesund gestartet." "$before" "$backup"
        return
    fi
    log "Version $to läuft."
    status DONE START "Version $to läuft."
    replace_self "$image-updater:$tag"
}

# Zuletzt den Updater selbst ersetzen. Das muss ein eigener, kurzlebiger Container erledigen: Compose stoppt dabei
# diesen Container und würde den Befehl sonst mittendrin abbrechen. Der Status steht da schon fest.
replace_self() {
    local config
    config="$(env_get DOCKER_CONFIG_DIR)"
    docker run -d --rm --name "nestwerk-updater-swap-$ID" --entrypoint sh \
        -v /var/run/docker.sock:/var/run/docker.sock -v "$INSTALL:$INSTALL" -v "${config:-/root/.docker}:/root/.docker:ro" \
        -w "$INSTALL" "$1" -c 'sleep 2; docker compose up -d --no-deps updater' >>"$LOG" 2>&1 \
        || log "Der Updater konnte sich nicht selbst ersetzen; er läuft in der alten Version weiter."
}

# Aufträge, die beim letzten Start mittendrin abbrachen (Updater neu gestartet)
recover() {
    local dir
    for dir in "$WORK"/results/*/; do
        OUT="${dir%/}"
        LOG="$OUT/log.txt"
        if [ -f "$OUT/status.json" ] && [ "$(jq -r .status "$OUT/status.json" 2>/dev/null)" = RUNNING ]; then
            log "Auftrag $(basename "$OUT") wurde unterbrochen."
            status FAILED "$(jq -r .step "$OUT/status.json")" \
                "Der Updater wurde während des Updates unterbrochen. Bitte prüfen, welche Version läuft."
        fi
    done
}

main() {
    mkdir -p "$WORK/jobs" "$WORK/results"
    # nur die Aufträge gehören der App; Ergebnisse und Lebenszeichen nur root (für die App lesbar)
    chown root:root "$WORK" "$WORK/results"
    chmod 755 "$WORK" "$WORK/results"
    chown "$APP_UID" "$WORK/jobs"
    recover
    while true; do
        touch "$WORK/updater.alive"
        for job in "$WORK"/jobs/*/; do
            local_id="$(basename "$job")"
            if [[ "$local_id" =~ ^[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$ ]] && [ ! -L "${job%/}" ] \
                    && app_file "${job%/}/ready" && [ ! -e "$WORK/results/$local_id" ]; then
                run_job "$local_id"
            fi
        done
        find "$WORK/jobs" "$WORK/results" -mindepth 1 -maxdepth 1 -type d -mtime +90 -exec rm -rf {} + 2>/dev/null
        sleep 5
    done
}

main "$@"
