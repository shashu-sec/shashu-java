# SHASHU Java SDK release gate. Exit non-zero on any failure.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Find-Mvn {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $hint = "C:\Users\sathi\.toolchain\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path $hint) { return $hint }
    throw "mvn not found"
}
function Find-Gitleaks {
    $cmd = Get-Command gitleaks -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($hint in @(
        "$env:TEMP\gitleaks\gitleaks.exe",
        "C:\Users\sathi\AppData\Local\Temp\gitleaks\gitleaks.exe",
        "D:\Trustgate\_agent\det-hardening-data\streamBCD\tools\gitleaks\bin\gitleaks.exe"
    )) {
        if (Test-Path $hint) { return $hint }
    }
    throw "gitleaks not found"
}
function Ensure-Osv {
    $cmd = Get-Command osv-scanner -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $dir = Join-Path $env:TEMP "osv-scanner"
    $exe = Join-Path $dir "osv-scanner.exe"
    if (Test-Path $exe) { return $exe }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $zip = Join-Path $dir "osv.zip"
    $url = "https://github.com/google/osv-scanner/releases/latest/download/osv-scanner_windows_amd64.exe"
    Write-Host "Downloading osv-scanner..."
    Invoke-WebRequest -Uri $url -OutFile $exe
    return $exe
}

$mvn = Find-Mvn
$env:Path = "$(Split-Path $mvn);$env:Path"

if (-not $env:JAVA_HOME_17 -and (Test-Path "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot")) {
    $env:JAVA_HOME_17 = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
}
if (-not $env:JAVA_HOME_21 -and (Test-Path "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot")) {
    $env:JAVA_HOME_21 = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
}

function Invoke-Mvn {
    param($javaHome, [string[]]$MvnArgs)
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;" + $env:Path
    & $mvn @MvnArgs
    if ($LASTEXITCODE -ne 0) { throw "mvn failed: $MvnArgs" }
}

Write-Host "== spotless:apply then check (google-java-format via Spotless) =="
& $mvn -B -q spotless:apply
if ($LASTEXITCODE -ne 0) { throw "spotless:apply" }
& $mvn -B -q spotless:check
if ($LASTEXITCODE -ne 0) { throw "spotless:check" }

$homes = @()
if ($env:JAVA_HOME_17) { $homes += @{ n = "17"; h = $env:JAVA_HOME_17 } }
if ($env:JAVA_HOME_21) { $homes += @{ n = "21"; h = $env:JAVA_HOME_21 } }
if ($homes.Count -eq 0) {
    $cur = $env:JAVA_HOME
    if (-not $cur) { throw "JAVA_HOME unset" }
    $homes += @{ n = "current"; h = $cur }
}

foreach ($j in $homes) {
    Write-Host "== mvn verify JDK $($j.n) (compiler -Werror, tests, javadoc, sources, spotbugs) =="
    Invoke-Mvn -javaHome $j.h -MvnArgs @("-B", "verify")
}

Write-Host "== jar contents =="
$jar = Join-Path $Root "target\shashu-sdk-1.2.0.jar"
$src = Join-Path $Root "target\shashu-sdk-1.2.0-sources.jar"
$jdoc = Join-Path $Root "target\shashu-sdk-1.2.0-javadoc.jar"
foreach ($p in @($jar, $src, $jdoc)) {
    if (-not (Test-Path $p)) { throw "missing $p" }
}
$list = & jar tf $jar
$bad = $list | Where-Object { $_ -match "_internal/|IDENTITY-I4|surefire|src/test" }
if ($bad) { $bad | ForEach-Object { Write-Host $_ }; throw "jar contains internals" }

Write-Host "== OSV-Scanner =="
$osv = Ensure-Osv
& $osv -r $Root --format table
if ($LASTEXITCODE -ne 0) { throw "osv-scanner" }

Write-Host "== gitleaks =="
$gl = Find-Gitleaks
& $gl detect --no-git --source $Root --redact
if ($LASTEXITCODE -ne 0) { throw "gitleaks" }

Write-Host "ALL CHECKS PASSED"
