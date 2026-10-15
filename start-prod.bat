@echo off
rem Startet Nestwerk im Produktivmodus per Docker Compose (compose.prod.yaml + compose.build.yaml).
rem Baut das App-Image lokal neu und startet Datenbank und App im Hintergrund (ohne Reverse Proxy).
rem Voraussetzungen: laufendes Docker mit Compose-Plugin und eine ausgefuellte .env.
cd /d "%~dp0"

rem .env beim ersten Start aus der Vorlage anlegen und zum Bearbeiten oeffnen
if not exist ".env" (
    copy ".env.example" ".env" >nul
    echo .env wurde aus .env.example angelegt.
    echo Bitte Passwoerter anpassen, speichern und das Skript erneut starten.
    start "" notepad ".env"
    exit /b 1
)

findstr /c:"=bitte-aendern" ".env" >nul
if not errorlevel 1 (
    echo In der .env stehen noch Platzhalter-Passwoerter ^(bitte-aendern^). Bitte zuerst anpassen.
    exit /b 1
)

rem Der initiale Admin wird nur mit einem Passwort ab 8 Zeichen angelegt, sonst bricht die App ab
set "ADMIN_PW="
for /f "usebackq tokens=1,* delims==" %%a in (".env") do if /i "%%a"=="NESTWERK_ADMIN_PASSWORD" set "ADMIN_PW=%%b"
if not defined ADMIN_PW goto admin_pw_short
if "%ADMIN_PW:~7,1%"=="" goto admin_pw_short
goto admin_pw_ok
:admin_pw_short
echo NESTWERK_ADMIN_PASSWORD in der .env muss mindestens 8 Zeichen lang sein.
exit /b 1
:admin_pw_ok
set "ADMIN_PW="

rem Der Entwicklungs-Container aus compose.yaml liegt im selben Compose-Projekt, keine Warnung dafuer
set COMPOSE_IGNORE_ORPHANS=True

rem Docker Desktop starten, falls die Engine nicht erreichbar ist
docker info >nul 2>&1
if not errorlevel 1 goto docker_ready
set "DOCKER_EXE=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_EXE%" (
    echo Docker Desktop nicht gefunden. Bitte Docker manuell starten.
    exit /b 1
)
echo Docker laeuft nicht - starte Docker Desktop...
start "" "%DOCKER_EXE%"
set /a DOCKER_WAIT=0
:wait_docker
timeout /t 3 /nobreak >nul
docker info >nul 2>&1
if not errorlevel 1 goto docker_ready
set /a DOCKER_WAIT+=1
if %DOCKER_WAIT% lss 60 goto wait_docker
echo Docker ist nach 3 Minuten noch nicht bereit. Abbruch.
exit /b 1
:docker_ready

rem --wait blockiert, bis die Healthchecks von db und app gruen sind
echo Baue und starte Nestwerk (compose.prod.yaml)...
docker compose -f compose.prod.yaml -f compose.build.yaml up -d --build --wait
if errorlevel 1 (
    echo Start fehlgeschlagen. Log der App:
    docker compose -f compose.prod.yaml -f compose.build.yaml logs --tail 50 app
    docker compose -f compose.prod.yaml -f compose.build.yaml logs --tail 200 app 2>nul | findstr /c:"password authentication failed" >nul
    if not errorlevel 1 (
        echo.
        echo Hinweis: Die Datenbank lehnt das Passwort ab. POSTGRES_PASSWORD gilt nur beim ersten Anlegen
        echo des Volumes db-data. Wurde es in der .env danach geaendert, bitte den alten Wert wieder eintragen
        echo oder das Passwort in der Datenbank per ALTER USER anpassen.
    )
    exit /b 1
)

set "APP_BIND=127.0.0.1:8080"
for /f "usebackq tokens=1,* delims==" %%a in (".env") do if /i "%%a"=="APP_BIND" set "APP_BIND=%%b"
echo.
echo Nestwerk laeuft auf http://%APP_BIND%
echo Logs:    docker compose -f compose.prod.yaml -f compose.build.yaml logs -f app
echo Stoppen: docker compose -f compose.prod.yaml -f compose.build.yaml down
