# opik.ps1

[CmdletBinding()]
param (
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$options = @()
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$dockerComposeDir = Join-Path $scriptDir "deployment\docker-compose"

$REQUIRED_CONTAINERS = @(
    "opik-clickhouse-1",
    "opik-mysql-1",
    "opik-python-backend-1",
    "opik-redis-1",
    "opik-frontend-1",
    "opik-backend-1",
    "opik-minio-1",
    "opik-zookeeper-1"
)

$GUARDRAILS_CONTAINERS = @(
    "opik-guardrails-backend-1"
)

function Get-Containers {
    $containers = $REQUIRED_CONTAINERS
    if ($GUARDRAILS_ENABLED) {
        $containers += $GUARDRAILS_CONTAINERS
    }
    return $containers
}

function Show-Usage {
    Write-Host 'Usage: opik.ps1 [OPTIONS]'
    Write-Host ''
    Write-Host 'Options:'
    Write-Host '  --verify          Check if all containers are healthy'
    Write-Host '  --info            Display welcome system status, only if all containers are running'
    Write-Host '  --stop            Stop all containers and clean up'
    Write-Host '  --build           Build containers before starting (can be combined with other flags)'
    Write-Host '  --debug           Enable debug mode (verbose output) (can be combined with other flags)'
    Write-Host '  --port-mapping    Enable port mapping for all containers by using the override file (can be combined with other flags)'
    Write-Host '  --guardrails      Enable guardrails profile (can be combined with other flags)'
    Write-Host '  --help            Show this help message'
    Write-Host ''
    Write-Host 'If no option is passed, the script will start missing containers and then show the system status.'
}

function Test-DockerStatus {
    try {
        $dockerInfo = docker info 2>&1
        if ($dockerInfo -match "error during connect") {
            Write-Host '[ERROR] Docker is not running or is not accessible. Please start Docker first.'
            exit 1
        }
    } catch {
        Write-Host '[ERROR] Failed to communicate with Docker. Please check if Docker is running and accessible.'
        exit 1
    }
}

function Test-ContainersStatus {
    [CmdletBinding()]
    param (
        [bool]$ShowOutput = $false
    )
    Test-DockerStatus
    $allOk = $true

    $containers = Get-Containers

    foreach ($container in $containers) {
        $status = docker inspect -f '{{.State.Status}}' $container 2>$null
        $health = docker inspect -f '{{.State.Health.Status}}' $container 2>$null

        if ([string]::IsNullOrEmpty($status)) { $status = 'not found' }

        if ($status -ne 'running') {
            Write-Host "[ERROR] $container is not running (status=$status)"
            $allOk = $false
        } elseif ($health -and $health -ne 'healthy') {
            Write-Host "[ERROR] $container is running but not healthy (health=$health)"
            $allOk = $false
        } elseif ($ShowOutput) {
            Write-Host "[OK] $container is running and healthy"
        }
    }

    return $allOk
}

function Send-InstallReport {
    param (
        [string]$Uuid,
        [string]$EventCompleted = $null,   # Pass "true" to send opik_os_install_completed
        [string]$StartTime = $null         # Optional ISO 8601 format
    )

    $DebugMode = $DEBUG_MODE -eq "true"
    $OpikUsageEnabled = $env:OPIK_USAGE_REPORT_ENABLED

    if ($OpikUsageEnabled -ne "true" -and $null -ne $OpikUsageEnabled) {
        if ($DebugMode) { Write-Host "[DEBUG] Usage reporting is disabled. Skipping install report." }
        return
    }

    $InstallMarkerFile = Join-Path $scriptDir ".opik_install_reported"

    if (Test-Path $InstallMarkerFile) {
        if ($DebugMode) { Write-Host "[DEBUG] Install report already sent; skipping." }
        return
    }

    $Timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")

    if ($EventCompleted -eq "true") {

        $EventType = "opik_os_install_completed"
        $EndTime = $Timestamp

        $Payload = @{
            anonymous_id = $Uuid
            event_type   = $EventType
            event_properties = @{
                start_time  = $StartTime
                end_time    = $EndTime
                script_type = "ps1"
            }
        }
    } else {
        $EventType = "opik_os_install_started"

        $Payload = @{
            anonymous_id = $Uuid
            event_type   = $EventType
            event_properties = @{
                start_time = $StartTime
                script_type = "ps1"
            }
        }
    }

    $JsonPayload = $Payload | ConvertTo-Json -Depth 3 -Compress
    $Url = "https://stats.comet.com/notify/event/"

    try {
        Invoke-WebRequest -Uri $Url -Method POST -ContentType "application/json" -Body $JsonPayload -UseBasicParsing | Out-Null
    } catch {
        Write-Warning "[WARN] Failed to send usage report: $_"
        return
    }

    if ($EventType -eq "opik_os_install_completed") {
        New-Item -ItemType File -Path $InstallMarkerFile -Force | Out-Null
        if ($DebugMode) { Write-Host "[DEBUG] Post-install report sent successfully." }
    } else {
        if ($DebugMode) { Write-Host "[DEBUG] Install started report sent successfully." }
    }
}

function Start-MissingContainers {
    Test-DockerStatus

    $Uuid = [guid]::NewGuid().ToString()
    $startTime = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

    Send-InstallReport -Uuid $uuid -EventCompleted "false" -StartTime $startTime

    if ($DEBUG_MODE) { Write-Host '[DEBUG] Checking required containers...' }
    $allRunning = $true

    $containers = Get-Containers

    foreach ($container in $containers) {
        $status = docker inspect -f '{{.State.Status}}' $container 2>$null
        $resolvedStatus = if ($status) { $status } else { 'not found' }

        if ($status -ne 'running') {
            if ($DEBUG_MODE) { Write-Host "[WARN] $container is not running (status: $resolvedStatus)" }
            $allRunning = $false
        } elseif ($DEBUG_MODE) {
            Write-Host "[OK] $container is already running"
        }
    }

    Write-Host '[INFO] Starting missing containers...'

    $dockerArgs = @("compose", "-f", (Join-Path $dockerComposeDir "docker-compose.yaml"))

    if ($PORT_MAPPING) {
        $dockerArgs += "-f", (Join-Path $dockerComposeDir "docker-compose.override.yaml")
    }

    if ($GUARDRAILS_ENABLED) {
        $dockerArgs += "--profile", "guardrails"
    }
    
    $dockerArgs += "up", "-d"

    if ($BUILD_MODE -eq "true") {
        $dockerArgs += "--build"
    }

    docker @dockerArgs | Where-Object { $_.Trim() -ne '' }

    Write-Host '[INFO] Waiting for all containers to be running and healthy...'
    $maxRetries = 60
    $interval = 1
    $allRunning = $true

    foreach ($container in $containers) {
        $retries = 0
        if ($DEBUG_MODE) { Write-Host "[DEBUG] Waiting for $container..." }

        while ($true) {
            $status = docker inspect -f '{{.State.Status}}' $container 2>$null
            $health = docker inspect -f '{{.State.Health.Status}}' $container 2>$null

            if (-not $health) { 
                $health = 'no health check defined' 
            }

            if ($status -ne 'running') {
                Write-Host "[ERROR] $container failed to start (status: $status)"
                break
            }

            if ($health -eq 'healthy') {
                if ($DEBUG_MODE) { Write-Host "[OK] $container is now running and healthy!" }
                break
            } elseif ($health -eq 'starting') {
                if ($DEBUG_MODE) { Write-Host "[INFO] $container is starting... retrying (${retries}s)" }
                Start-Sleep -Seconds $interval
                $retries++
                if ($retries -ge $maxRetries) {
                    Write-Host "[WARN] $container is still not healthy after ${maxRetries}s"
                    $allRunning = $false
                    break
                }
            } else {
                Write-Host "[INFO] $container health state is '$health'"
                $allRunning = $false
                break
            }
        }
    }

    if ($allRunning) {
        Send-InstallReport -Uuid $uuid -EventCompleted "true" -StartTime $startTime
    }
}

function Stop-Containers {
    Test-DockerStatus
    Write-Host '[INFO] Stopping all required containers...'

    $dockerArgs = @("compose", "-f", (Join-Path $dockerComposeDir "docker-compose.yaml"))

    if ($PORT_MAPPING) {
        $dockerArgs += "-f", (Join-Path $dockerComposeDir "docker-compose.override.yaml")
    }
    
    if ($GUARDRAILS_ENABLED) {
        $dockerArgs += "--profile", "guardrails"
    }
    
    $dockerArgs += "down"
    docker @dockerArgs
    Write-Host '[OK] All containers stopped and cleaned up!'
}

function Show-Banner {
    Test-DockerStatus
    $frontendPort = docker inspect -f '{{ (index (index .NetworkSettings.Ports "5173/tcp") 0).HostPort }}' opik-frontend-1 2>$null
    if (-not $frontendPort) { $frontendPort = 5173 }
    $uiUrl = "http://localhost:$frontendPort"

    Write-Host ''
    Write-Host '╔═════════════════════════════════════════════════════════════════╗'
    Write-Host '║                                                                 ║'
    Write-Host '║                       🚀 OPIK PLATFORM 🚀                       ║'
    Write-Host '║                                                                 ║'
    Write-Host '╠═════════════════════════════════════════════════════════════════╣'
    Write-Host '║                                                                 ║'
    Write-Host '║  ✅ All services started successfully!                          ║'
    Write-Host '║                                                                 ║'
    Write-Host '║  📊 Access the UI:                                              ║'
    Write-Host "║     $uiUrl                                       ║"
    Write-Host '║                                                                 ║'
    Write-Host '║  🛠️  Configure the Python SDK:                                  ║'
    Write-Host '║    - Be sure Python 3.x is installed and available via PATH     ║'
    Write-Host '║    - `pip install opik` # (or `py -m pip install opik`)         ║'
    Write-Host '║    - `opik configure`                                           ║'
    Write-Host '║                                                                 ║'
    Write-Host '║  📚 Documentation: https://www.comet.com/docs/opik/             ║'
    Write-Host '║                                                                 ║'
    Write-Host '║  💬 Need help? Join our community: https://chat.comet.com       ║'
    Write-Host '║                                                                 ║'
    Write-Host '╚═════════════════════════════════════════════════════════════════╝'
}

function Get-VerifyCommand {
    if ($GUARDRAILS_ENABLED) {
        return ".\opik.ps1 --guardrails --verify"
    }
    return ".\opik.ps1 --verify"
}

$BUILD_MODE = $false
$DEBUG_MODE = $false
$PORT_MAPPING = $false
$GUARDRAILS_ENABLED = $false
$env:OPIK_FRONTEND_FLAVOR = "default"
$env:TOGGLE_GUARDRAILS_ENABLED = "false"

if ($options -contains '--build') {
    $BUILD_MODE = $true
    docker buildx bake --help *>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        # TODO: Enable bake once the issue with Windows paths is resolved:
        # - https://github.com/docker/for-win/issues/14761
        # - https://github.com/docker/buildx/issues/1028
        # - https://github.com/docker/compose/issues/12669
        Write-Host '[INFO] Bake is not available for docker compose on Windows yet. Not using it for builds'
        $env:COMPOSE_BAKE = "false"
    } else {
        Write-Host '[INFO] Bake is not available on Docker Buildx. Not using it for builds'
        $env:COMPOSE_BAKE = "false"
    }
    $options = $options | Where-Object { $_ -ne '--build' }
}

if ($options -contains '--debug') {
    $DEBUG_MODE = $true
    Write-Host '[DEBUG] Debug mode enabled.'
    $options = $options | Where-Object { $_ -ne '--debug' }
}

if ($options -contains '--port-mapping') {
    $PORT_MAPPING = $true
    $options = $options | Where-Object { $_ -ne '--port-mapping' }
}

if ($options -contains '--guardrails') {
    $GUARDRAILS_ENABLED = $true
    $env:OPIK_FRONTEND_FLAVOR = "guardrails"
    $env:TOGGLE_GUARDRAILS_ENABLED = "true"
    $options = $options | Where-Object { $_ -ne '--guardrails' }
}

# Get the first remaining option
$option = if ($options.Count -gt 0) { $options[0] } else { '' }

switch ($option) {
    '--verify' {
        Write-Host '[INFO] Verifying container health...'
        $result = Test-ContainersStatus -ShowOutput:$true
        if ($result) { exit 0 } else { exit 1 }
    }
    '--info' {
        Write-Host '[INFO] Checking if all containers are up before displaying system status...'
        if (Test-ContainersStatus -ShowOutput:$true) {
            Show-Banner
            exit 0
        } else {
            Write-Host "[WARN] Some containers are not running/healthy. Please run '$(Get-VerifyCommand)'."
            exit 1
        }
    }
    '--stop' {
        Stop-Containers
        exit 0
    }
    '--help' {
        Show-Usage
        exit 0
    }
    '' {
        Write-Host '[DEBUG] Checking container status and starting missing ones...'
        Start-MissingContainers
        Start-Sleep -Seconds 2
        Write-Host '[DEBUG] Re-checking container status...'
        if (Test-ContainersStatus -ShowOutput:$true) {
            Show-Banner
        } else {
            Write-Host "[WARN] Some containers are still not healthy. Please check manually using '$(Get-VerifyCommand)'."
            exit 1
        }
    }
    Default {
        Write-Host "[ERROR] Unknown option: $option"
        Show-Usage
        exit 1
    }
}
