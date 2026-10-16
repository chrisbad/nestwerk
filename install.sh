#!/usr/bin/env bash
# shellcheck disable=SC1111 # deutsche Anführungszeichen in Meldungen sind Absicht
# Nestwerk auf einem Linux-Server installieren (getestet mit Debian 12/13 und Ubuntu 24.04).
#
#   sudo ./install.sh                 aus dem entpackten Installationspaket (Release-Asset)
#   curl -fsSL …/install.sh | sudo bash   lädt die übrigen Dateien selbst (bei privatem Repo GITHUB_TOKEN setzen)
#
# Das Skript richtet bei Bedarf Docker und einen zentralen Reverse Proxy (nginx-proxy + Let's Encrypt) ein, erzeugt
# alle Passwörter und startet Nestwerk. Ohne Rückfragen (z. B. für Tests): NESTWERK_NONINTERACTIVE=1 und die
# Werte als Umgebungsvariablen (NESTWERK_DOMAIN, LETSENCRYPT_EMAIL, NESTWERK_MODE=proxy|direct, …).
set -euo pipefail

REPO="chrisbad/nestwerk"
INSTALL_DIR="${NESTWERK_DIR:-/opt/nestwerk}"
PROXY_DIR="${NESTWERK_PROXY_DIR:-/opt/proxy}"
PROXY_NETWORK="${PROXY_NETWORK:-proxy}"
VERSION="${NESTWERK_VERSION:-latest}"
NONINTERACTIVE="${NESTWERK_NONINTERACTIVE:-0}"
if SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd)"; then :; else SRC_DIR="$(pwd)"; fi
APP_UID=1001

# --- Ausgabe und Fragen ---------------------------------------------------------------------

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
say() { printf '  %s\n' "$*"; }
warn() { printf '\033[33m  ! %s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ask VAR "Frage" "Vorgabe": nimmt einen vorhandenen Wert der Variable, sonst fragt es (oder Vorgabe)
ask() {
    local var="$1" prompt="$2" default="${3:-}" value="${!1:-}"
    if [ -z "$value" ]; then
        if [ "$NONINTERACTIVE" = "1" ]; then
            value="$default"
        else
            read -r -p "  $prompt${default:+ [$default]}: " value </dev/tty || true
            value="${value:-$default}"
        fi
    fi
    printf -v "$var" '%s' "$value"
}

# confirm "Frage" j|n → 0 bei ja
confirm() {
    local prompt="$1" default="${2:-j}" answer
    if [ "$NONINTERACTIVE" = "1" ]; then
        answer="$default"
    else
        read -r -p "  $prompt [$( [ "$default" = "j" ] && echo "J/n" || echo "j/N")]: " answer </dev/tty || true
        answer="${answer:-$default}"
    fi
    case "$answer" in j|J|ja|Ja|y|Y|yes) return 0 ;; *) return 1 ;; esac
}

secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-"${1:-32}"
    else
        tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}"
    fi
}

# --- Dateien aus dem Paket oder von GitHub ---------------------------------------------------

fetch() {
    local file="$1" target="$2"
    if [ -f "$SRC_DIR/$file" ]; then
        cp "$SRC_DIR/$file" "$target"
        return
    fi
    local ref="main"
    [ "$VERSION" != "latest" ] && ref="v$VERSION"
    local url="https://raw.githubusercontent.com/$REPO/$ref/$file"
    local auth=()
    [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: token $GITHUB_TOKEN")
    curl -fsSL ${auth[@]+"${auth[@]}"} "$url" -o "$target" \
        || die "Konnte $file nicht laden ($url). Bei privatem Repo GITHUB_TOKEN setzen oder das Installationspaket nutzen."
}

# --- Voraussetzungen ---------------------------------------------------------------------------

check_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        [ "${NESTWERK_SKIP_DOCKER_INSTALL:-0}" = "1" ] && die "Docker fehlt."
        confirm "Docker ist nicht installiert. Jetzt mit dem offiziellen Skript (get.docker.com) installieren?" j \
            || die "Ohne Docker geht es nicht."
        curl -fsSL https://get.docker.com | sh
    fi
    docker info >/dev/null 2>&1 || die "Docker läuft nicht oder ist für diesen Benutzer nicht erreichbar (sudo?)."
    local compose
    compose="$(docker compose version --short 2>/dev/null || true)"
    [ -n "$compose" ] || die "Das Docker-Compose-Plugin fehlt (Paket docker-compose-plugin)."
    local major minor
    major="${compose%%.*}"; minor="$(echo "$compose" | cut -d. -f2)"
    if [ "$major" -lt 2 ] || { [ "$major" -eq 2 ] && [ "$minor" -lt 24 ]; }; then
        die "Docker Compose $compose ist zu alt, nötig ist mindestens 2.24."
    fi
}

port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        ss -ltn "( sport = :$1 )" 2>/dev/null | grep -q LISTEN
    else
        return 1
    fi
}

choose_mode() {
    if [ -n "${NESTWERK_MODE:-}" ]; then
        MODE="$NESTWERK_MODE"
    elif docker network inspect "$PROXY_NETWORK" >/dev/null 2>&1; then
        say "Gefunden: Docker-Netz „$PROXY_NETWORK“ eines vorhandenen nginx-proxy – Nestwerk hängt sich dort an."
        MODE="proxy"
        PROXY_EXISTS=1
    elif port_in_use 80 || port_in_use 443; then
        warn "Port 80/443 ist belegt, vermutlich durch einen eigenen Reverse Proxy."
        say "Nestwerk lauscht dann nur auf 127.0.0.1:8080; dein Proxy leitet die Domain dorthin."
        MODE="direct"
    elif confirm "Zentralen Reverse Proxy (nginx-proxy + Let's Encrypt) für alle Dienste einrichten?" j; then
        MODE="proxy"
    else
        MODE="direct"
    fi
    [ "$MODE" = "proxy" ] || [ "$MODE" = "direct" ] || die "NESTWERK_MODE muss proxy oder direct sein."
}

# --- Ablauf ------------------------------------------------------------------------------------

main() {
    bold "Nestwerk installieren"
    [ -w "$(dirname "$INSTALL_DIR")" ] || [ "$(id -u)" = "0" ] || die "Bitte mit sudo ausführen."
    if [ -f "$INSTALL_DIR/.env" ]; then
        say "Nestwerk ist in $INSTALL_DIR bereits installiert. Zum Aktualisieren: $INSTALL_DIR/update.sh"
        exit 0
    fi
    check_docker
    PROXY_EXISTS=0
    choose_mode

    bold "Einstellungen"
    ask NESTWERK_DOMAIN "Domain für Nestwerk (z. B. nestwerk.example.org)" ""
    [ -n "$NESTWERK_DOMAIN" ] || [ "$MODE" = "direct" ] || die "Ohne Domain kein Zertifikat."
    if [ "$MODE" = "proxy" ]; then
        ask LETSENCRYPT_EMAIL "E-Mail für Let's Encrypt (Hinweise zu Zertifikaten)" ""
    fi
    ask NESTWERK_ADMIN_USERNAME "Benutzername des ersten Administrators" "admin"
    ask NESTWERK_EXTERNAL_BACKUP_HOST_DIR "Verzeichnis einer externen Backup-Platte (leer = keine)" ""
    local admin_password db_password secret_key base_url
    admin_password="${NESTWERK_ADMIN_PASSWORD:-$(secret 20)}"
    db_password="$(secret 32)"
    secret_key="$(secret 48)"
    base_url="https://${NESTWERK_DOMAIN:-localhost}"
    [ "$MODE" = "direct" ] && [ -z "$NESTWERK_DOMAIN" ] && base_url="http://localhost:8080"

    bold "Dateien"
    mkdir -p "$INSTALL_DIR"
    fetch compose.prod.yaml "$INSTALL_DIR/compose.prod.yaml"
    fetch compose.proxy.yaml "$INSTALL_DIR/compose.proxy.yaml"
    fetch update.sh "$INSTALL_DIR/update.sh"
    chmod +x "$INSTALL_DIR/update.sh"
    local compose_file="compose.prod.yaml"
    [ "$MODE" = "proxy" ] && compose_file="compose.prod.yaml:compose.proxy.yaml"
    local external_line="#NESTWERK_EXTERNAL_BACKUP_HOST_DIR="
    mkdir -p "$INSTALL_DIR/external-backup"
    chown "$APP_UID" "$INSTALL_DIR/external-backup" 2>/dev/null || true
    if [ -n "$NESTWERK_EXTERNAL_BACKUP_HOST_DIR" ]; then
        mkdir -p "$NESTWERK_EXTERNAL_BACKUP_HOST_DIR"
        chown "$APP_UID" "$NESTWERK_EXTERNAL_BACKUP_HOST_DIR" 2>/dev/null \
            || warn "Konnte $NESTWERK_EXTERNAL_BACKUP_HOST_DIR nicht für den App-Benutzer (UID $APP_UID) freigeben."
        external_line="NESTWERK_EXTERNAL_BACKUP_HOST_DIR=$NESTWERK_EXTERNAL_BACKUP_HOST_DIR"
    fi
    umask 077
    cat >"$INSTALL_DIR/.env" <<EOF
# Erzeugt von install.sh am $(date '+%d.%m.%Y %H:%M'). Diese Datei enthält Passwörter – gut aufbewahren!
COMPOSE_FILE=$compose_file
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-nestwerk}
NESTWERK_VERSION=$VERSION
NESTWERK_DIR=$INSTALL_DIR
POSTGRES_DB=nestwerk
POSTGRES_USER=nestwerk
POSTGRES_PASSWORD=$db_password
NESTWERK_ADMIN_USERNAME=$NESTWERK_ADMIN_USERNAME
NESTWERK_ADMIN_PASSWORD=$admin_password
NESTWERK_SECRET_KEY=$secret_key
NESTWERK_DOMAIN=$NESTWERK_DOMAIN
LETSENCRYPT_EMAIL=${LETSENCRYPT_EMAIL:-}
NESTWERK_BASE_URL=$base_url
APP_BIND=${APP_BIND:-127.0.0.1:8080}
PROXY_NETWORK=$PROXY_NETWORK
$external_line
MAIL_HOST=
MAIL_PORT=587
MAIL_USER=
MAIL_PASSWORD=
MAIL_FROM=
EOF
    umask 022
    say ".env mit neuen Passwörtern angelegt: $INSTALL_DIR/.env"

    if [ "$MODE" = "proxy" ] && [ "$PROXY_EXISTS" = "0" ]; then
        bold "Reverse Proxy"
        mkdir -p "$PROXY_DIR/vhost.d"
        fetch proxy/compose.yaml "$PROXY_DIR/compose.yaml"
        cat >"$PROXY_DIR/.env" <<EOF
COMPOSE_PROJECT_NAME=${PROXY_PROJECT_NAME:-proxy}
PROXY_NETWORK=$PROXY_NETWORK
LETSENCRYPT_EMAIL=${LETSENCRYPT_EMAIL:-}
PROXY_HTTP_PORT=${PROXY_HTTP_PORT:-80}
PROXY_HTTPS_PORT=${PROXY_HTTPS_PORT:-443}
EOF
        # PROXY_SERVICES ist absichtlich eine Liste von Diensten (Tests starten nur nginx-proxy)
        # shellcheck disable=SC2086
        (cd "$PROXY_DIR" && docker compose up -d ${PROXY_SERVICES:-})
        say "nginx-proxy läuft ($PROXY_DIR). Weitere Dienste hängen sich über das Netz „$PROXY_NETWORK“ an."
    fi
    if [ "$MODE" = "proxy" ]; then
        local vhost_dir="$PROXY_DIR/vhost.d"
        if [ -d "$vhost_dir" ]; then
            fetch proxy/nestwerk-vhost.conf "$vhost_dir/$NESTWERK_DOMAIN"
            docker exec nginx-proxy nginx -s reload >/dev/null 2>&1 || true
        else
            warn "vhost.d des vorhandenen Proxys nicht gefunden: für große Sicherungen dort"
            warn "„client_max_body_size 4g;“ für $NESTWERK_DOMAIN eintragen."
        fi
    fi

    bold "Nestwerk starten"
    cd "$INSTALL_DIR"
    if [ "${NESTWERK_SKIP_PULL:-0}" != "1" ] && ! docker compose pull app updater >/dev/null 2>&1; then
        warn "Das Image ghcr.io/$REPO:$VERSION ließ sich nicht laden (privates Paket?)."
        local gh_user gh_token
        ask gh_user "GitHub-Benutzername" ""
        if [ "$NONINTERACTIVE" = "1" ]; then
            gh_token="${GITHUB_TOKEN:-}"
        else
            read -r -s -p "  GitHub-Token mit Recht read:packages: " gh_token </dev/tty || true
            echo
        fi
        [ -n "$gh_token" ] || die "Ohne Zugang zu ghcr.io kein Image."
        printf '%s' "$gh_token" | docker login ghcr.io -u "$gh_user" --password-stdin >/dev/null \
            || die "Anmeldung bei ghcr.io fehlgeschlagen."
        docker compose pull app updater || die "Das Image ließ sich auch nach der Anmeldung nicht laden."
    fi
    docker compose up -d --wait || {
        docker compose logs --tail 50 app
        die "Nestwerk ist nicht gestartet (Log oben)."
    }

    if [ "$MODE" = "proxy" ] && [ "${NESTWERK_SKIP_CERT_WAIT:-0}" != "1" ]; then
        say "Warte auf das Zertifikat für $NESTWERK_DOMAIN (bis zu 3 Minuten) …"
        for _ in $(seq 1 36); do
            curl -fsS -o /dev/null "https://$NESTWERK_DOMAIN/login" 2>/dev/null && break
            sleep 5
        done
        curl -fsS -o /dev/null "https://$NESTWERK_DOMAIN/login" 2>/dev/null \
            || warn "Noch kein Zertifikat. Zeigt die Domain per DNS auf diesen Server und ist Port 80 offen?"
    fi

    bold "Fertig"
    say "Adresse:        $base_url"
    say "Administrator:  $NESTWERK_ADMIN_USERNAME"
    say "Passwort:       $admin_password   (jetzt notieren und nach dem ersten Login ändern)"
    say "Einstellungen:  $INSTALL_DIR/.env  (enthält NESTWERK_SECRET_KEY – mit sichern!)"
    say "Aktualisieren:  in Nestwerk unter „Updates“ (oder $INSTALL_DIR/update.sh)"
    [ "$MODE" = "direct" ] && say "Eigener Proxy:  Domain an http://${APP_BIND:-127.0.0.1:8080} weiterleiten."
    say "Sicherungen richtest du in Nestwerk unter „Sicherungen“ im Benutzermenü ein."
}

main "$@"
