# Starts Nestwerk against a throwaway Postgres container, smoke-tests it and stops it again.
# Usage (from the repository root, Windows PowerShell 5.1):
#   .claude\skills\run-nestwerk\driver.ps1 start   # container + app on http://localhost:8099
#   .claude\skills\run-nestwerk\driver.ps1 smoke   # login as admin and fetch a page (default /calendar/week)
#   .claude\skills\run-nestwerk\driver.ps1 stop    # stop app and remove the container
param(
    [Parameter(Mandatory)][ValidateSet('start', 'smoke', 'stop')][string]$Action,
    [string]$Path = '/calendar/week'
)

$ErrorActionPreference = 'Stop'
$Port = 8099
$DbPort = 55432
$Container = 'nestwerk-run-check'
$AdminPassword = 'test-admin-pw'
$Java = Join-Path $env:USERPROFILE '.jdks\openjdk-26\bin\java.exe'
$Jar = 'nestwerk-app/target/nestwerk-0.0.1-SNAPSHOT.jar'
$Log = Join-Path $env:TEMP 'nestwerk-run.log'
$PidFile = Join-Path $env:TEMP 'nestwerk-run.pid'
$Cookies = Join-Path $env:TEMP 'nestwerk-run.cookies'
$Base = "http://localhost:$Port"

function Start-Nestwerk {
    if (-not (Test-Path $Jar)) { throw "$Jar missing - build first with mvnw.cmd -DskipTests package" }
    docker rm -f $Container 2>$null | Out-Null
    docker run -d --rm --name $Container -e POSTGRES_DB=nestwerk -e POSTGRES_USER=nestwerk `
        -e POSTGRES_PASSWORD=check -p "${DbPort}:5432" postgres:17 | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        docker exec $Container pg_isready -U nestwerk 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }

    # Templates and static files come straight from the sources, so UI edits show up on reload.
    $root = (Get-Location).Path.Replace('\', '/')
    $env:DB_URL = "jdbc:postgresql://localhost:$DbPort/nestwerk"
    $env:DB_USER = 'nestwerk'
    $env:DB_PASSWORD = 'check'
    $arguments = @(
        '-jar', $Jar,
        "--server.port=$Port",
        "--nestwerk.admin.password=$AdminPassword",
        '--spring.docker.compose.enabled=false',
        "--spring.thymeleaf.prefix=file:$root/nestwerk-web/src/main/resources/templates/",
        '--spring.thymeleaf.cache=false',
        "--spring.web.resources.static-locations=file:$root/nestwerk-web/src/main/resources/static/",
        '--spring.web.resources.cache.period=0'
    )
    $process = Start-Process -FilePath $Java -ArgumentList $arguments -RedirectStandardOutput $Log `
        -RedirectStandardError "$Log.err" -WindowStyle Hidden -PassThru
    Set-Content -Path $PidFile -Value $process.Id -Encoding ascii

    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 2
        if ($process.HasExited) { throw "App exited - see $Log and $Log.err" }
        $health = curl.exe -s "$Base/actuator/health"
        if ($health -match '"UP"') { Write-Output "Nestwerk is up on $Base (admin / $AdminPassword)"; return }
    }
    throw "App did not become healthy - see $Log"
}

function Invoke-Smoke {
    Remove-Item $Cookies -ErrorAction SilentlyContinue
    $loginPage = curl.exe -s -c $Cookies "$Base/login" | Out-String
    if ($loginPage -notmatch 'name="_csrf"\s+value="([^"]+)"') { throw 'No CSRF token on /login' }
    $token = $Matches[1]
    curl.exe -s -o NUL -b $Cookies -c $Cookies -d "username=admin&password=$AdminPassword&_csrf=$token" "$Base/login"
    $status = curl.exe -s -o NUL -w '%{http_code}' -b $Cookies "$Base$Path"
    $title = (curl.exe -s -b $Cookies "$Base$Path" | Out-String) -replace '(?s).*?<title>(.*?)</title>.*', '$1'
    Write-Output "GET $Path -> $status, title: $($title.Trim())"
    if ($status -ne '200') { throw "Unexpected status $status" }
}

function Stop-Nestwerk {
    if (Test-Path $PidFile) {
        Stop-Process -Id (Get-Content $PidFile) -Force -ErrorAction SilentlyContinue
        Remove-Item $PidFile
    }
    docker rm -f $Container 2>$null | Out-Null
    Write-Output 'Nestwerk stopped, container removed'
}

switch ($Action) {
    'start' { Start-Nestwerk }
    'smoke' { Invoke-Smoke }
    'stop' { Stop-Nestwerk }
}
