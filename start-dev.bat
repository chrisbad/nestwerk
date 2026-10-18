@echo off
rem Startet Nestwerk lokal (Datenbank wird per Docker Compose automatisch gestartet).
rem Voraussetzungen: JDK 25 oder neuer und laufendes Docker.
cd /d "%~dp0"

rem JDK >= 25 verwenden (System-JAVA_HOME zeigt auf JDK 17)
if exist "%USERPROFILE%\.jdks\openjdk-26\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\openjdk-26"

if "%NESTWERK_ADMIN_PASSWORD%"=="" set NESTWERK_ADMIN_PASSWORD=changeme
if "%NESTWERK_ADMIN_USERNAME%"=="" set NESTWERK_ADMIN_USERNAME=admin
rem Port 8080 ist lokal teils belegt, daher 8081
if "%SERVER_PORT%"=="" set SERVER_PORT=8081

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

echo Starte Nestwerk auf http://localhost:%SERVER_PORT% (Login: %NESTWERK_ADMIN_USERNAME% / %NESTWERK_ADMIN_PASSWORD%)
call "%~dp0mvnw.cmd" -pl nestwerk-app -am compile spring-boot:run
