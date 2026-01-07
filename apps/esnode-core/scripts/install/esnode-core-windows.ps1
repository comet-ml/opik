#Requires -Version 5
param(
    [string]$Source = ".\esnode-core.exe",
    [string]$InstallDir = "C:\Program Files\ESNODE",
    [switch]$RegisterService,
    [string]$ServiceName = "esnode-core",
    [string]$ServiceDisplayName = "ESNODE-Core",
    [string]$Bind = "0.0.0.0:9100",
    [string]$NssmPath = "C:\Program Files\nssm\nssm.exe"
)

function Ensure-Path($dir) {
    $current = [Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($current -notlike "*$dir*") {
        Write-Host "Adding $dir to system PATH"
        $new = "$current;$dir"
        [Environment]::SetEnvironmentVariable("Path", $new, "Machine")
    }
}

if (-not (Test-Path $Source)) {
    Write-Error "Binary not found at $Source"
    exit 1
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item $Source (Join-Path $InstallDir "esnode-core.exe") -Force
Ensure-Path $InstallDir

if ($RegisterService) {
    if (-not (Test-Path $NssmPath)) {
        Write-Warning "NSSM not found at $NssmPath; skipping service registration."
    } else {
        & $NssmPath install $ServiceName (Join-Path $InstallDir "esnode-core.exe") "daemon" "--listen-address" $Bind
        & $NssmPath set $ServiceName DisplayName $ServiceDisplayName
        & $NssmPath set $ServiceName Start SERVICE_AUTO_START
        & $NssmPath start $ServiceName
        Write-Host "Service $ServiceName installed and started."
    }
}

Write-Host "ESNODE-Core installed to $InstallDir. Open a new shell to use 'esnode-core'."
