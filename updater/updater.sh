#!/usr/bin/env bash
# shellcheck disable=SC1111 # deutsche Anführungszeichen in Meldungen sind Absicht
# Nestwerk-Updater: arbeitet die Update-Aufträge der App ab (Design: docs/superpowers/specs/2026-10-16-updates-design.md).
#
# Die App legt /work/jobs/<id>/ an: request.json, files/ (Installationsdateien der Zielversion), zuletzt „ready“.
# Der Updater antwortet mit status.json, log.txt und backup (Dateiname der Sicherung) und meldet sich alle paar
# Sekunden über /work/updater.alive. Ablauf: Sicherung → Dateien tauschen → Image laden → Neustart; schlägt etwas
# fehl, zurück auf die alte Version und – falls die neue schon migriert hatte – auf die Sicherung.
set -uo pipefail

WORK="${UPDATER_WORK:-/work}"
INSTALL="${NESTWERK_DIR:?NESTWERK_DIR fehlt}"
WAIT="${UPDATER_WAIT_TIMEOUT:-300}"
FILES=(compose.prod.yaml compose.proxy.yaml update.sh)
JOB=""
LOG=/dev/null

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }

# status ZUSTAND SCHRITT MELDUNG – atomar, damit die App nie eine halbe Datei liest
status() {
    jq -n --arg s "$1" --arg step "$2" --arg m "$3" --arg t "$(now)" \
        '{status: $s, step: $step, message: $m, updatedAt: $t}' >"$JOB/status.json.tmp" \
        && mv -f "$JOB/status.json.tmp" "$JOB/status.json"
}

compose() { (cd "$INSTALL" && docker compose "$@") >>"$LOG" 2>&1; }

env_set() {
    if grep -qE "^$1=" "$INSTALL/.env"; then
        sed -i "s|^$1=.*|$1=$2|" "$INSTALL/.env"
    else
        printf '%s=%s\n' "$1" "$2" >>"$INSTALL/.env"
    fi
}

# Höchste angewandte Migration; leer, wenn die Datenbank nicht antwortet
flyway_rank() {
    (cd "$INSTALL" && docker compose exec -T db sh -c \
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT coalesce(max(installed_rank), 0) FROM flyway_schema_history"') \
        2>>"$LOG" | tr -d '[:space:]'
}

save_previous() {
    mkdir -p "$JOB/previous"
    cp -p "$INSTALL/.env" "$JOB/previous/.env"
    for f in "${FILES[@]}"; do
        [ -f "$INSTALL/$f" ] && cp -p "$INSTALL/$f" "$JOB/previous/$f"
    done
}

restore_previous() {
    cp -p "$JOB/previous/.env" "$INSTALL/.env"
    for f in "${FILES[@]}"; do
        [ -f "$JOB/previous/$f" ] && cp -p "$JOB/previous/$f" "$INSTALL/$f"
    done
}

# rollback GRUND RANG_VORHER
rollback() {
    local reason="$1" before="$2" after backup
    log "Fehler: $reason Zurück auf die vorherige Version."
    status RUNNING ROLLBACK "$reason Zurück auf die vorherige Version …"
    restore_previous
    after="$(flyway_rank)"
    if [ -z "$after" ] || [ "$after" -gt "$before" ]; then
        backup="$(cat "$JOB/backup" 2>/dev/null || true)"
        log "Die neue Version hat die Datenbank verändert (Migration $before → ${after:-?}); Sicherung $backup wird eingespielt."
        compose stop app
        if [ -z "$backup" ] || ! compose run --rm --no-deps -T app \
                "--nestwerk.backup.restore-and-exit=$backup" --server.port=0; then
            status FAILED ROLLBACK "$reason Auch das Zurücksetzen der Daten ist fehlgeschlagen. Bitte per SSH prüfen; die Sicherung von vorher heißt ${backup:-(unbekannt)}."
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
    JOB="$1"
    LOG="$JOB/log.txt"
    : >"$LOG"
    local id tag to before
    id="$(basename "$JOB")"
    tag="$(jq -r '.imageTag // empty' "$JOB/request.json" 2>/dev/null)"
    to="$(jq -r '.toVersion // empty' "$JOB/request.json" 2>/dev/null)"
    if ! [[ "$tag" =~ ^[A-Za-z0-9._-]{1,60}$ ]]; then
        status FAILED BACKUP "Der Auftrag ist ungültig (Version „$tag“)."
        return
    fi
    for f in "${FILES[@]}"; do
        if [ ! -s "$JOB/files/$f" ]; then
            status FAILED FILES "Im Auftrag fehlt die Datei $f."
            return
        fi
    done
    log "Update auf $to (Image-Tag $tag)"

    status RUNNING BACKUP "Sicherung mit der laufenden Version …"
    if ! compose run --rm --no-deps -T app --nestwerk.backup.run-and-exit=true --server.port=0 \
            "--nestwerk.backup.result-file=/data/updater/jobs/$id/backup"; then
        log "Die Sicherung ist fehlgeschlagen."
        status FAILED BACKUP "Die Sicherung ist fehlgeschlagen; es wurde nichts verändert."
        return
    fi
    log "Gesichert: $(cat "$JOB/backup" 2>/dev/null)"
    before="$(flyway_rank)"
    before="${before:-0}"
    save_previous

    status RUNNING FILES "Installationsdateien der neuen Version …"
    for f in "${FILES[@]}"; do
        cp "$JOB/files/$f" "$INSTALL/$f"
    done
    chmod +x "$INSTALL/update.sh"
    env_set NESTWERK_VERSION "$tag"
    grep -q '^NESTWERK_DIR=' "$INSTALL/.env" || env_set NESTWERK_DIR "$INSTALL"

    status RUNNING PULL "Neue Version wird geladen …"
    if ! compose pull app updater; then
        rollback "Die neue Version ließ sich nicht laden." "$before"
        return
    fi

    status RUNNING START "Neustart mit der neuen Version …"
    if ! compose up -d --wait --wait-timeout "$WAIT" db app; then
        rollback "Die neue Version ist nicht gesund gestartet." "$before"
        return
    fi
    log "Version $to läuft."
    status DONE START "Version $to läuft."
    # zuletzt sich selbst ersetzen (falls sich der Updater geändert hat); der Status steht schon fest
    compose up -d --no-deps updater
}

# Aufträge, die beim letzten Start mittendrin abbrachen (Updater neu gestartet)
recover() {
    local job
    for job in "$WORK"/jobs/*/; do
        JOB="${job%/}"
        LOG="$JOB/log.txt"
        if [ -f "$JOB/status.json" ] && [ "$(jq -r .status "$JOB/status.json" 2>/dev/null)" = RUNNING ]; then
            log "Auftrag $(basename "$JOB") wurde unterbrochen."
            status FAILED "$(jq -r .step "$JOB/status.json")" \
                "Der Updater wurde während des Updates unterbrochen. Bitte prüfen, welche Version läuft."
        fi
    done
}

main() {
    mkdir -p "$WORK/jobs"
    # die App (Benutzer 1001) legt hier Aufträge an; der Updater läuft als root
    chown "${APP_UID:-1001}" "$WORK" "$WORK/jobs"
    recover
    while true; do
        touch "$WORK/updater.alive"
        for job in "$WORK"/jobs/*/; do
            job="${job%/}"
            if [ -f "$job/ready" ] && [ ! -f "$job/status.json" ]; then
                run_job "$job"
            fi
        done
        find "$WORK/jobs" -mindepth 1 -maxdepth 1 -type d -mtime +90 -exec rm -rf {} + 2>/dev/null
        sleep 5
    done
}

main "$@"
