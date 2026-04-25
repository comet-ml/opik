# opik.ps1

[CmdletBinding()]
param (
    [string]$Runtime = "",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$options = @()
)

# Accept --runtime passed bash-style in $options
$runtimeIdx = [array]::IndexOf([string[]]$options, "--runtime")
if ($runtimeIdx -ge 0 -and $runtimeIdx + 1 -lt $options.Count) {
    $Runtime = $options[$runtimeIdx + 1]
    $options = $options | Where-Object { $_ -ne "--runtime" -and $_ -ne $Runtime }
}

# Env var fallback
if (-not $Runtime) { $Runtime = $env:OPIK_CONTAINER_RUNTIME }

# Skip runtime detection for --help: no container runtime needed to print usage.
$_skipRuntime = $options -contains "--help" -or $options -contains "-help"

# Validate if explicitly set
if ($Runtime -and $Runtime -notin @("docker", "podman")) {
    Write-Host "[ERROR] Invalid -Runtime value: '$Runtime'. Must be 'docker' or 'podman'."
    exit 1
}

# Validate binary exists if explicitly set
if ($Runtime) {
    if (-not (Get-Command $Runtime -ErrorAction SilentlyContinue)) {
        Write-Host "[ERROR] Runtime '$Runtime' was requested but is not installed or not on PATH."
        exit 1
    }
}

# Auto-detect and compose resolution — skipped for --help invocations
if (-not $_skipRuntime) {
    if (-not $Runtime) {
        if (Get-Command docker -ErrorAction SilentlyContinue) {
            docker info *>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { $Runtime = "docker" }
        }
        if (-not $Runtime) {
            if (Get-Command podman -ErrorAction SilentlyContinue) {
                podman info *>&1 | Out-Null
                if ($LASTEXITCODE -eq 0) { $Runtime = "podman" }
            }
        }
        if (-not $Runtime) {
            Write-Host "[ERROR] Neither Docker nor Podman found. Please install one first."
            exit 1
        }
    }

    $env:CONTAINER_RUNTIME = $Runtime

    if ($Runtime -eq "podman") {
        $env:OPIK_HOST_GATEWAY = "host.containers.internal"
        podman compose version *>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $script:ComposeBinary = "podman"
            $script:ComposeSubArgs = @("compose")
        } elseif (Get-Command "podman-compose" -ErrorAction SilentlyContinue) {
            $script:ComposeBinary = "podman-compose"
            $script:ComposeSubArgs = @()
        } else {
            Write-Host "[ERROR] Podman found but no compose tool available."
            Write-Host "   Option 1: Upgrade to Podman 4.7+ (includes 'podman compose')"
            Write-Host "   Option 2: pip install podman-compose>=1.0"
            exit 1
        }
        $env:COMPOSE_BAKE = "false"
    } else {
        $env:OPIK_HOST_GATEWAY = "host.docker.internal"
        $script:ComposeBinary = "docker"
        $script:ComposeSubArgs = @("compose")
    }
}

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$dockerComposeDir = Join-Path $scriptDir "deployment\docker-compose"

$INFRA_CONTAINERS = @(
    "opik-clickhouse-1",
    "opik-mysql-1",
    "opik-redis-1",
    "opik-minio-1",
    "opik-zookeeper-1"
)

$BACKEND_CONTAINERS = @(
    "opik-python-backend-1",
    "opik-backend-1"
)

$OPIK_CONTAINERS = @(
    "opik-frontend-1"
)

$GUARDRAILS_CONTAINERS = @(
    "opik-guardrails-backend-1"
)

$LOCAL_BE_CONTAINERS = @(
    "opik-python-backend-1",
    "opik-frontend-1"
)

$LOCAL_BE_FE_CONTAINERS = @(
    "opik-python-backend-1"
)

function Get-Containers {
    $containers = @()
    
    if ($INFRA) {
        $containers = $INFRA_CONTAINERS
    } elseif ($BACKEND) {
        $containers = $INFRA_CONTAINERS + $BACKEND_CONTAINERS
    } elseif ($LOCAL_BE) {
        $containers = $INFRA_CONTAINERS + $LOCAL_BE_CONTAINERS
    } elseif ($LOCAL_BE_FE) {
        $containers = $INFRA_CONTAINERS + $LOCAL_BE_FE_CONTAINERS
    } else {
        # Full Opik (default)
        $containers = $INFRA_CONTAINERS + $BACKEND_CONTAINERS + $OPIK_CONTAINERS
    }
    
    # Add guardrails containers if enabled
    if ($GUARDRAILS_ENABLED) {
        $containers += $GUARDRAILS_CONTAINERS
    }
    
    return $containers
}

function Write-DebugLog {
    param([string]$Message)
    $DebugMode = $DEBUG_MODE -eq "true"
    if ($DebugMode) { 
        Write-Host $Message 
    }
}

function Initialize-BuildxBake {
    if ($Runtime -eq "podman") {
        $env:COMPOSE_BAKE = "false"
        return
    }
    if ($BUILD_MODE -eq "true") {
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
    }
}

function Get-DockerComposeCommand {
    $dockerArgs = $script:ComposeSubArgs + @("-f", (Join-Path $dockerComposeDir "docker-compose.yaml"))

    if ($PORT_MAPPING) {
        $dockerArgs += "-f", (Join-Path $dockerComposeDir "docker-compose.override.yaml")
    }

    # Add profiles based on the selected mode (accumulative)
    if ($INFRA) {
        # No profile needed - infrastructure services start by default
    } elseif ($BACKEND) {
        $dockerArgs += "--profile", "backend"
    } elseif ($LOCAL_BE) {
        $dockerArgs += "-f", (Join-Path $dockerComposeDir "docker-compose.local-be.yaml")
        $dockerArgs += "--profile", "local-be"
    } elseif ($LOCAL_BE_FE) {
        $dockerArgs += "-f", (Join-Path $dockerComposeDir "docker-compose.local-be-fe.yaml")
        $dockerArgs += "--profile", "local-be-fe"
    } else {
        # Full Opik (default) - includes all dependencies
        $dockerArgs += "--profile", "opik"
    }
    
    # Always add guardrails profile if enabled
    if ($GUARDRAILS_ENABLED) {
        $dockerArgs += "--profile", "guardrails"
    }
    
    return $dockerArgs
}

function Get-SystemInfo {
    # Function to gather system info without failing the script
    # All commands wrapped with error handling and fallbacks
    
    # OS detection - safe with fallback
    $osInfo = "unknown"
    try {
        $osVersion = [System.Environment]::OSVersion
        if ($osVersion) {
            $osInfo = "Windows $($osVersion.Version.Major).$($osVersion.Version.Minor).$($osVersion.Version.Build)"
        }
    } catch {
        Write-DebugLog "[WARN] Failed to get OS info: $_"
    }
    
    # Runtime version - safe with fallback
    $runtimeVersion = "unknown"
    try {
        if ($Runtime -eq "podman") {
            $podmanOut = (podman --version 2>&1 | Out-String).Trim()
            if ($podmanOut -match 'podman version (.+)') {
                $runtimeVersion = $Matches[1].Trim()
            }
        } else {
            $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
            if ($dockerCmd) {
                $dockerOutput = (docker --version 2>&1 | Out-String).Trim()
                if ($dockerOutput -match 'Docker version ([^,\s]+)') {
                    $runtimeVersion = $Matches[1]
                }
            }
        }
    } catch {
        Write-DebugLog "[WARN] Failed to get runtime version: $_"
    }

    # Compose version - safe with fallback
    $composeVersion = "unknown"
    try {
        if ($Runtime -eq "podman") {
            $composeOut = (& $script:ComposeBinary $script:ComposeSubArgs version 2>&1 | Out-String).Trim()
            if ($composeOut -match 'version\s+(.+)') {
                $composeVersion = $Matches[1].Trim()
            }
        } else {
            $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
            if ($dockerCmd) {
                $composeOutput = (docker compose version 2>&1 | Out-String).Trim()
                if ($composeOutput -match 'Docker Compose version (.+)$') {
                    $composeVersion = $Matches[1].Trim()
                }
            }
            if ($composeVersion -eq "unknown") {
                $dockerComposeCmd = Get-Command docker-compose -ErrorAction SilentlyContinue
                if ($dockerComposeCmd) {
                    $composeV1Output = (docker-compose version --short 2>&1 | Out-String).Trim()
                    if (-not [string]::IsNullOrWhiteSpace($composeV1Output)) {
                        $composeVersion = $composeV1Output
                    }
                }
            }
        }
    } catch {
        Write-DebugLog "[WARN] Failed to get compose version: $_"
    }

    return @{
        Os                   = $osInfo
        RuntimeName          = $Runtime
        RuntimeVersion       = $runtimeVersion
        ComposeVersion       = $composeVersion
        DockerVersion        = $runtimeVersion
        DockerComposeVersion = $composeVersion
    }
}

function Show-Usage {
    Write-Host 'Usage: opik.ps1 [OPTIONS]'
    Write-Host ''
    Write-Host 'Options:'
    Write-Host '  --verify          Check if all containers are healthy'
    Write-Host '  --info            Display welcome system status, only if all containers are running'
    Write-Host '  --stop            Stop all containers and clean up'
    Write-Host '  --clean           Stop all containers and remove all Opik data volumes (WARNING: ALL OPIK DATA WILL BE LOST)'
    Write-Host '  --demo-data       Triggers creation of demo data, assumes all required services (backend, python-backend, frontend etc.) are already running'
    Write-Host '  --build           Build containers before starting (can be combined with other flags)'
    Write-Host '  --debug           Enable debug mode (verbose output) (can be combined with other flags)'
    Write-Host '  --port-mapping    Enable port mapping for all containers by using the override file (can be combined with other flags)'
    Write-Host '  --infra           Start only infrastructure services (MySQL, Redis, ClickHouse, ZooKeeper, MinIO etc.)'
    Write-Host '  --backend         Start only infrastructure + backend services (Backend, Python Backend etc.)'
    Write-Host '  --local-be        Start all services EXCEPT backend (for local backend development)'
    Write-Host '  --local-be-fe     Start only infrastructure + Python backend (for local backend + frontend development)'
    Write-Host '  --guardrails      Enable guardrails profile (can be combined with other flags)'
    Write-Host '  -Runtime VALUE    Set container runtime: docker or podman (default: auto-detect)'
    Write-Host '  --runtime VALUE   Same as -Runtime (bash-compatible form, passed via options)'
    Write-Host '  --help            Show this help message'
    Write-Host ''
    Write-Host 'If no option is passed, the script will start missing containers and then show the system status.'
}

function Test-DockerStatus {
    try {
        & $Runtime info *>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] ${Runtime} is not running or not accessible. Please start ${Runtime} first."
            exit 1
        }
    } catch {
        Write-Host "[ERROR] Failed to communicate with ${Runtime}. Please check if it is running."
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
        $status = & $Runtime inspect -f '{{.State.Status}}' $container 2>$null
        $health = & $Runtime inspect -f '{{.State.Health.Status}}' $container 2>$null

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

# Wait for a container to complete and return its exit code
# Args: $1 = container name, $2 = timeout in seconds (default: 60)
# Returns: 0 if container exits with code 0, 1 otherwise
function Wait-ContainerCompletion {
    param(
        [string]$ContainerName,
        [int]$MaxWait = 60
    )

    Write-DebugLog "[DEBUG] Waiting for $ContainerName to complete (timeout: ${MaxWait}s)..."
    $count = 0

    while ($count -lt $MaxWait) {
        $status = & $Runtime inspect -f '{{.State.Status}}' $ContainerName 2>$null
        if ([string]::IsNullOrEmpty($status)) { $status = 'not_found' }

        if ($status -eq 'exited') {
            $exitCode = & $Runtime inspect -f '{{.State.ExitCode}}' $ContainerName 2>$null
            if ([string]::IsNullOrEmpty($exitCode)) { $exitCode = 1 }
            Write-DebugLog "[DEBUG] $ContainerName exited with code: $exitCode"
            return $exitCode
        } elseif ($status -eq 'not_found') {
            Write-Host "[ERROR] $ContainerName container not found"
            return 1
        }

        Start-Sleep -Seconds 1
        $count++
    }

    Write-Host "[ERROR] Timeout waiting for $ContainerName to complete"
    & $Runtime logs $ContainerName 2>$null
    return 1
}

function Send-InstallReport {
    param (
        [string]$Uuid,
        [string]$EventCompleted = $null,   # Pass "true" to send opik_os_install_completed
        [string]$StartTime = $null         # Optional ISO 8601 format
    )

    $OpikUsageEnabled = $env:OPIK_USAGE_REPORT_ENABLED

    # Configure usage reporting based on deployment mode
    # $PROFILE_COUNT: if > 0, it's a partial profile; if = 0, it's full Opik
    if ($script:PROFILE_COUNT -gt 0) {
        # Partial profile mode - disable reporting
        $OpikUsageEnabled = "false"
        Write-DebugLog "[DEBUG] Disabling usage reporting due to not starting the full Opik suite"
    }

    if ($OpikUsageEnabled -ne "true" -and $null -ne $OpikUsageEnabled) {
        Write-DebugLog "[DEBUG] Usage reporting is disabled. Skipping install report."
        return
    }

    $InstallMarkerFile = Join-Path $scriptDir ".opik_install_reported"

    if (Test-Path $InstallMarkerFile) {
        Write-DebugLog "[DEBUG] Install report already sent; skipping."
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
                event_ver = "1"
                script_type = "ps1"
            }
        }
    } else {
        $EventType = "opik_os_install_started"
        
        # Get system info safely - wrapped to prevent script failure
        try {
            $SystemInfo = Get-SystemInfo
        } catch {
            Write-DebugLog "[WARN] Failed to get system info, using defaults: $_"
            $SystemInfo = @{
                Os = "unknown"
                RuntimeName = "unknown"
                RuntimeVersion = "unknown"
                ComposeVersion = "unknown"
                DockerVersion = "unknown"
                DockerComposeVersion = "unknown"
            }
        }
        
        Write-DebugLog "[DEBUG] System info: OS=$($SystemInfo.Os), Runtime=$($SystemInfo.RuntimeName) $($SystemInfo.RuntimeVersion), Compose=$($SystemInfo.ComposeVersion)"

        $Payload = @{
            anonymous_id = $Uuid
            event_type   = $EventType
            event_properties = @{
                start_time             = $StartTime
                event_ver              = "1"
                script_type            = "ps1"
                os                     = $SystemInfo.Os
                container_runtime      = $SystemInfo.RuntimeName
                runtime_version        = $SystemInfo.RuntimeVersion
                compose_version        = $SystemInfo.ComposeVersion
                docker_version         = $SystemInfo.DockerVersion
                docker_compose_version = $SystemInfo.DockerComposeVersion
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
        Write-DebugLog "[DEBUG] Post-install report sent successfully."
    } else {
        Write-DebugLog "[DEBUG] Install started report sent successfully."
    }
}

function Start-MissingContainers {
    Test-DockerStatus

    # Generate a run-scoped anonymous ID for this installation session
    $Uuid = [guid]::NewGuid().ToString()
    # Export persistent install UUID so docker-compose and services can consume it
    $env:OPIK_ANONYMOUS_ID = $Uuid
    $startTime = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

    Write-DebugLog "[DEBUG] OPIK_ANONYMOUS_ID = $Uuid"

    Send-InstallReport -Uuid $uuid -EventCompleted "false" -StartTime $startTime

    Write-DebugLog '[DEBUG] Checking required containers...'
    $allRunning = $true

    $containers = Get-Containers

    foreach ($container in $containers) {
        $status = & $Runtime inspect -f '{{.State.Status}}' $container 2>$null
        $resolvedStatus = if ($status) { $status } else { 'not found' }

        if ($status -ne 'running') {
            Write-DebugLog "[WARN] $container is not running (status: $resolvedStatus)"
            $allRunning = $false
        } else {
            Write-DebugLog "[OK] $container is already running"
        }
    }

    Write-Host '[INFO] Starting missing containers...'

    Initialize-BuildxBake

    $dockerArgs = Get-DockerComposeCommand
    $dockerArgs += "up", "-d"

    if ($BUILD_MODE -eq "true") {
        $dockerArgs += "--build"
    }

    & $script:ComposeBinary @dockerArgs | Where-Object { $_.Trim() -ne '' }

    Write-Host '[INFO] Waiting for all containers to be running and healthy...'
    $maxRetries = 60
    $interval = 1
    $allRunning = $true

    foreach ($container in $containers) {
        $retries = 0
        Write-DebugLog "[DEBUG] Waiting for $container..."

        while ($true) {
            $status = & $Runtime inspect -f '{{.State.Status}}' $container 2>$null
            $health = & $Runtime inspect -f '{{.State.Health.Status}}' $container 2>$null

            if (-not $health) { 
                $health = 'no health check defined' 
            }

            if ($status -ne 'running') {
                Write-Host "[ERROR] $container failed to start (status: $status)"
                $allRunning = $false
                break
            }

            if ($health -eq 'healthy') {
                Write-DebugLog "[OK] $container is now running and healthy!"
                break
            } elseif ($health -eq 'starting') {
                Write-DebugLog "[INFO] $container is starting... retrying (${retries}s)"
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
        New-OpikConfigIfMissing
    } else {
        Write-DebugLog '[DEBUG] Skipping install completed report due to startup errors.'
    }
}

function Stop-Containers {
    Test-DockerStatus
    Write-Host '[INFO] Stopping all required containers...'

    $dockerArgs = Get-DockerComposeCommand
    $dockerArgs += "down"
    & $script:ComposeBinary @dockerArgs
    Write-Host '[OK] All containers stopped and cleaned up!'
}

function Remove-OpikData {
    Test-DockerStatus
    Write-Host '[WARN] WARNING: This will remove ALL Opik data including:'
    Write-Host '   - MySQL (projects, datasets etc.)'
    Write-Host '   - ClickHouse (traces, spans, etc.)'
    Write-Host '   - Etc.'
    Write-Host ''
    Write-Host '[INFO] Stopping all containers and removing volumes...'

    $dockerArgs = Get-DockerComposeCommand
    $dockerArgs += "down", "-v"
    
    Write-DebugLog "[DEBUG] Running: $script:ComposeBinary $($dockerArgs -join ' ')"
    & $script:ComposeBinary @dockerArgs
    Write-Host '[OK] All containers stopped and data volumes removed!'
}

function New-DemoData {
    Test-DockerStatus
    Write-Host '[INFO] Creating demo data...'

    Initialize-BuildxBake

    # Build the complete command
    # --no-deps: Don't start dependent services
    # Add --build flag if BUILD_MODE is set
    $dockerArgs = Get-DockerComposeCommand
    $dockerArgs += "up", "--no-deps", "-d"
    
    if ($BUILD_MODE -eq "true") {
        $dockerArgs += "--build"
    }
    
    $dockerArgs += "demo-data-generator"

    Write-DebugLog "[DEBUG] Running: $script:ComposeBinary $($dockerArgs -join ' ')"
    & $script:ComposeBinary @dockerArgs

    if ($LASTEXITCODE -ne 0) {
        Write-Host '[ERROR] Failed to start demo-data-generator'
        return 1
    }

    # Wait for the container to finish and check its exit code
    $exitCode = Wait-ContainerCompletion -ContainerName "opik-demo-data-generator-1"
    if ($exitCode -eq 0) {
        Write-Host '[OK] Demo data created successfully!'
        return 0
    } else {
        Write-Host '[ERROR] Failed to create demo data'
        return 1
    }
}

function Get-UIUrl {
    $frontendPort = & $Runtime inspect -f '{{ (index (index .NetworkSettings.Ports "5173/tcp") 0).HostPort }}' opik-frontend-1 2>$null
    if (-not $frontendPort) { $frontendPort = 5173 }
    return "http://localhost:$frontendPort"
}

function New-OpikConfigIfMissing {
    # Cross-platform home directory handling
    # Use $HOME automatic variable as final fallback (always set by PowerShell)
    $homeDir = if ($env:USERPROFILE) { 
        $env:USERPROFILE 
    } elseif ($env:HOME) { 
        $env:HOME 
    } else { 
        $HOME  # PowerShell automatic variable (not environment variable)
    }
    $configFile = Join-Path $homeDir ".opik.config"
    
    if (Test-Path $configFile) {
        Write-DebugLog "[DEBUG] .opik.config file already exists, skipping creation"
        return
    }
    
    Write-DebugLog "[DEBUG] Creating .opik.config file at $configFile"
    
    $uiUrl = Get-UIUrl
    
    $configContent = @"
[opik]
url_override = $uiUrl/api/
workspace = default
"@
    
    $configContent | Out-File -FilePath $configFile -Encoding UTF8
    Write-DebugLog "[DEBUG] .opik.config file created successfully with URL: $uiUrl/api/"
}

function Show-Banner {
    Test-DockerStatus
    $uiUrl = Get-UIUrl

    Write-Host ''
    Write-Host '╔═════════════════════════════════════════════════════════════════╗'
    Write-Host '║                                                                 ║'
    Write-Host '║                       🚀 OPIK PLATFORM 🚀                       ║'
    Write-Host '║                                                                 ║'
    Write-Host '╠═════════════════════════════════════════════════════════════════╣'
    Write-Host '║                                                                 ║'
    if ($GUARDRAILS_ENABLED) {
        Write-Host '║  ✅ Guardrails services started successfully!                   ║'
    }
    if ($INFRA) {
        Write-Host '║  ✅ Infrastructure services started successfully!               ║'
        Write-Host '║                                                                 ║'
    } elseif ($BACKEND) {
        Write-Host '║  ✅ Backend services started successfully!                      ║'
        Write-Host '║                                                                 ║'
    } elseif ($LOCAL_BE_FE) {
        Write-Host '║  ✅ Local backend + frontend mode services started!             ║'
        Write-Host '║                                                                 ║'
        Write-Host '║  ⚙️  Configuration:                                              ║'
        Write-Host '║     Backend is NOT running in Docker                            ║'
        Write-Host '║     Frontend is NOT running in Docker                           ║'
        Write-Host '║     Port mapping: ENABLED (required for local processes)        ║'
        Write-Host '║                                                                 ║'
        Write-Host '║  📊 Access the UI (start backend + frontend first):             ║'
        Write-Host '║     http://localhost:5174                                       ║'
        Write-Host '║                                                                 ║'
    } elseif ($LOCAL_BE) {
        Write-Host '║  ✅ Local backend mode services started successfully!           ║'
        Write-Host '║                                                                 ║'
        Write-Host '║  ⚙️  Backend Configuration:                                      ║'
        Write-Host '║     Backend is NOT running in Docker                            ║'
        Write-Host '║     Start your local backend on port 8080                       ║'
        Write-Host '║     Frontend will proxy to: http://localhost:8080               ║'
        Write-Host '║     Port mapping: ENABLED (required for local processes)        ║'
        Write-Host '║                                                                 ║'
        Write-Host '║  📊 Access the UI (start backend first):                        ║'
        Write-Host "║     $uiUrl                                       ║"
        Write-Host '║                                                                 ║'
    } else {
        Write-Host '║  ✅ All services started successfully!                          ║'
        Write-Host '║                                                                 ║'
        Write-Host '║  📊 Access the UI:                                              ║'
        Write-Host "║     $uiUrl                                       ║"
        Write-Host '║                                                                 ║'
        Write-Host '║  🛠️  Install the Python SDK:                                     ║'
        Write-Host '║    - Be sure Python 3.x is installed and available via PATH     ║'
        Write-Host '║    - `pip install opik` # (or `py -m pip install opik`)         ║'
    }
    Write-Host '║                                                                 ║'
    Write-Host '║  📚 Documentation: https://www.comet.com/docs/opik/             ║'
    Write-Host '║                                                                 ║'
    Write-Host '║  💬 Need help? Join our community: https://chat.comet.com       ║'
    Write-Host '║                                                                 ║'
    Write-Host '╚═════════════════════════════════════════════════════════════════╝'
}

function Get-StartCommand {
    $cmd = ".\opik.ps1"
    
    if ($BUILD_MODE) {
        $cmd += " --build"
    }
    if ($DEBUG_MODE) {
        $cmd += " --debug"
    }
    if ($PORT_MAPPING) {
        $cmd += " --port-mapping"
    }
    if ($INFRA) {
        $cmd += " --infra"
    } elseif ($BACKEND) {
        $cmd += " --backend"
    } elseif ($LOCAL_BE) {
        $cmd += " --local-be"
    } elseif ($LOCAL_BE_FE) {
        $cmd += " --local-be-fe"
    }
    if ($GUARDRAILS_ENABLED) {
        $cmd += " --guardrails"
    }

    return $cmd
}

function Get-VerifyCommand {
    $cmd = ".\opik.ps1"
    
    if ($INFRA) {
        $cmd += " --infra"
    } elseif ($BACKEND) {
        $cmd += " --backend"
    } elseif ($LOCAL_BE) {
        $cmd += " --local-be"
    } elseif ($LOCAL_BE_FE) {
        $cmd += " --local-be-fe"
    }
    if ($GUARDRAILS_ENABLED) {
        $cmd += " --guardrails"
    }

    return "$cmd --verify"
}

$BUILD_MODE = $false
$DEBUG_MODE = $false
$PORT_MAPPING = $false
$GUARDRAILS_ENABLED = $false
# PowerShell persists environment variables across script runs, so we need to reset them
$env:OPIK_REVERSE_PROXY_URL = ""
$env:OPIK_FRONTEND_FLAVOR = "default"
$env:TOGGLE_GUARDRAILS_ENABLED = "false"
# Default: full opik (all profiles)
$INFRA = $false
$BACKEND = $false
$LOCAL_BE = $false
$LOCAL_BE_FE = $false

if ($options -contains '--build') {
    $BUILD_MODE = $true
    $options = $options | Where-Object { $_ -ne '--build' }
}

if ($options -contains '--debug') {
    $DEBUG_MODE = $true
    Write-DebugLog '[DEBUG] Debug mode enabled.'
    $options = $options | Where-Object { $_ -ne '--debug' }
}

if ($options -contains '--port-mapping') {
    $PORT_MAPPING = $true
    $options = $options | Where-Object { $_ -ne '--port-mapping' }
}

# Check for profile flags
if ($options -contains '--infra') {
    $INFRA = $true
    $options = $options | Where-Object { $_ -ne '--infra' }
}

if ($options -contains '--backend') {
    $BACKEND = $true
    # Enable CORS for frontend development
    $env:CORS = "true"
    $options = $options | Where-Object { $_ -ne '--backend' }
}

# Check --local-be-fe BEFORE --local-be (more specific first or regex will cause a script failure)
if ($options -contains '--local-be-fe') {
    $LOCAL_BE_FE = $true
    $PORT_MAPPING = $true  # Required for local processes to connect to infrastructure
    $env:OPIK_REVERSE_PROXY_URL = "http://$($env:OPIK_HOST_GATEWAY):8080"
    $options = $options | Where-Object { $_ -ne '--local-be-fe' }
}

if ($options -contains '--local-be') {
    $LOCAL_BE = $true
    $PORT_MAPPING = $true  # Required for local processes to connect to infrastructure
    $env:OPIK_FRONTEND_FLAVOR = "local_be"
    $options = $options | Where-Object { $_ -ne '--local-be' }
}

if ($options -contains '--guardrails') {
    $GUARDRAILS_ENABLED = $true
    # Only override flavor if not already set by local-be
    if ($env:OPIK_FRONTEND_FLAVOR -eq "default") {
        $env:OPIK_FRONTEND_FLAVOR = "guardrails"
    }
    $env:TOGGLE_GUARDRAILS_ENABLED = "true"
    $options = $options | Where-Object { $_ -ne '--guardrails' }
}

# Count active partial profiles
$PROFILE_COUNT = 0
if ($INFRA) { $PROFILE_COUNT++ }
if ($BACKEND) { $PROFILE_COUNT++ }
if ($LOCAL_BE) { $PROFILE_COUNT++ }
if ($LOCAL_BE_FE) { $PROFILE_COUNT++ }

# Validate mutually exclusive profile flags
if ($PROFILE_COUNT -gt 1) {
    Write-Host "❌ Error: --infra, --backend, --local-be, and --local-be-fe flags are mutually exclusive."
    Write-Host "   Choose one of the following:"
    Write-Host "   • .\opik.ps1 --infra        (infrastructure services only)"
    Write-Host "   • .\opik.ps1 --backend      (infrastructure + backend services)"
    Write-Host "   • .\opik.ps1 --local-be     (all services except backend - for local backend development)"
    Write-Host "   • .\opik.ps1 --local-be-fe  (infrastructure + Python backend - for local BE+FE development)"
    Write-Host "   • .\opik.ps1                (full Opik suite - default)"
    exit 1
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
            Write-Host "[WARN] Some containers are not running/healthy. Please run '$(Get-StartCommand)' to start them."
            exit 1
        }
    }
    '--stop' {
        Stop-Containers
        exit $LASTEXITCODE
    }
    '--clean' {
        Remove-OpikData
        exit $LASTEXITCODE
    }
    '--demo-data' {
        $exitCode = New-DemoData
        exit $exitCode
    }
    '--help' {
        Show-Usage
        exit 0
    }
    '' {
        Write-Host '🔍 Checking container status and starting missing ones...'
        Start-MissingContainers
        Start-Sleep -Seconds 2
        Write-Host '🔄 Re-checking container status...'
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
