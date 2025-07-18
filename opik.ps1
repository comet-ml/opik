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

# Local development uses specific services defined in LOCAL_DEVELOPMENT_SERVICES (excludes backend/frontend containers)
# Note: demo-data-generator is excluded because it depends on frontend/python-backend containers
$LOCAL_DEVELOPMENT_SERVICES = @("mysql", "redis", "clickhouse", "zookeeper", "minio", "mc")

# Colored output functions for local development
function Write-StatusInfo {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Blue
}

function Write-StatusSuccess {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-StatusWarning {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Write-StatusError {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Test-CommandExists {
    param([string]$Command)
    $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
}

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
    Write-Host '  --local           Start local development environment (containers + local backend/frontend)'
    Write-Host '  --migrate         Run database migrations (use with --local)'
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
    Write-Host '║    - `opik configure --use-local`                               ║'
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

# Local development functions

function Test-LocalRequirements {
    Write-StatusInfo "Checking local development requirements..."

    if (-not (Test-CommandExists "mvn")) {
        Write-StatusError "Maven is not installed. Please install Maven first."
        exit 1
    }

    if (-not (Test-CommandExists "node")) {
        Write-StatusError "Node.js is not installed. Please install Node.js first."
        exit 1
    }

    if (-not (Test-CommandExists "npm")) {
        Write-StatusError "npm is not installed. Please install npm first."
        exit 1
    }

    Write-StatusSuccess "All local development requirements are met"
}

function Set-NginxConfigLocal {
    Write-StatusInfo "Configuring nginx for local development..."

    $nginxConfig = Join-Path $scriptDir "deployment\docker-compose\nginx_default_local.conf"

    if (Test-Path $nginxConfig) {
        $content = Get-Content $nginxConfig -Raw
        if ($content -match "proxy_pass http://backend:8080;") {
            $content = $content -replace "proxy_pass http://backend:8080;", "proxy_pass http://host.docker.internal:8080;"
            Set-Content -Path $nginxConfig -Value $content
            Write-StatusSuccess "Nginx configuration updated for local development"
        } elseif ($content -match "proxy_pass http://host.docker.internal:8080;") {
            Write-StatusInfo "Nginx configuration already set for local development"
        } else {
            Write-StatusWarning "Could not find expected proxy_pass configuration in nginx file"
        }
    } else {
        Write-StatusWarning "Nginx configuration file not found: $nginxConfig"
    }
}

function Set-FrontendConfigLocal {
    Write-StatusInfo "Configuring frontend for local development..."

    $frontendDir = Join-Path $scriptDir "apps\opik-frontend"
    $envFile = Join-Path $frontendDir ".env.development"

    if (-not (Test-Path $envFile)) {
        Write-StatusWarning ".env.development file not found, creating it..."
    }

    $envContent = @"
VITE_BASE_URL=/
VITE_BASE_API_URL=http://localhost:8080
"@

    Set-Content -Path $envFile -Value $envContent
    Write-StatusSuccess "Frontend environment configured for local development"
}

function Start-LocalContainers {
    Write-StatusInfo "Starting required containers for local development (supporting services only, excluding backend/frontend/python-backend containers)..."

    # Change to script directory
    Set-Location $scriptDir

    # Configure nginx and frontend for local development
    Set-NginxConfigLocal
    Set-FrontendConfigLocal

    # Start containers using docker compose with port mapping override
    # Force port mapping to true for local development
    $dockerArgs = @(
        "compose",
        "-f", "deployment\docker-compose\docker-compose.yaml",
        "-f", "deployment\docker-compose\docker-compose.override.yaml",
        "up", "-d"
    ) + $LOCAL_DEVELOPMENT_SERVICES

    docker @dockerArgs | Where-Object { $_.Trim() -ne '' }

    Write-StatusInfo "Waiting for containers to be healthy..."

    # Wait for containers to be healthy
    $maxRetries = 60
    $interval = 2

    # Check container health
    # Check container health for supporting services only
    $containerHealthChecks = @("opik-mysql-1", "opik-redis-1", "opik-clickhouse-1", "opik-zookeeper-1", "opik-minio-1")
    
    foreach ($container in $containerHealthChecks) {
        Write-StatusInfo "Waiting for $container..."
        for ($i = 1; $i -le $maxRetries; $i++) {
            $health = docker inspect -f '{{.State.Health.Status}}' $container 2>$null
            if ($health -eq "healthy") {
                Write-StatusSuccess "$container is healthy"
                break
            }
            if ($i -eq $maxRetries) {
                if ($container -eq "opik-python-backend-1") {
                    Write-StatusWarning "$container may not be fully healthy, but continuing..."
                } else {
                    Write-StatusError "$container failed to become healthy after $maxRetries attempts"
                    exit 1
                }
            }
            Start-Sleep -Seconds $interval
        }
    }

    Write-StatusSuccess "All required containers for local development are running"
}

function Build-BackendLocal {
    Write-StatusInfo "Building backend with Maven (skipping tests)..."

    $backendDir = Join-Path $scriptDir "apps\opik-backend"
    Set-Location $backendDir

    # Clean and install, skipping tests
    & mvn clean install -DskipTests

    if ($LASTEXITCODE -eq 0) {
        Write-StatusSuccess "Backend built successfully"
    } else {
        Write-StatusError "Backend build failed"
        exit 1
    }
}

function Start-BackendLocal {
    Write-StatusInfo "Starting backend locally..."

    $backendDir = Join-Path $scriptDir "apps\opik-backend"
    Set-Location $backendDir

    # Set environment variables for local development
    $env:CORS = "true"
    $env:STATE_DB_PROTOCOL = "jdbc:mysql://"
    $env:STATE_DB_URL = "localhost:3306/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true"
    $env:STATE_DB_DATABASE_NAME = "opik"
    $env:STATE_DB_USER = "opik"
    $env:STATE_DB_PASS = "opik"
    $env:ANALYTICS_DB_MIGRATIONS_URL = "jdbc:clickhouse://localhost:8123"
    $env:ANALYTICS_DB_MIGRATIONS_USER = "opik"
    $env:ANALYTICS_DB_MIGRATIONS_PASS = "opik"
    $env:ANALYTICS_DB_PROTOCOL = "HTTP"
    $env:ANALYTICS_DB_HOST = "localhost"
    $env:ANALYTICS_DB_PORT = "8123"
    $env:ANALYTICS_DB_DATABASE_NAME = "opik"
    $env:ANALYTICS_DB_USERNAME = "opik"
    $env:ANALYTICS_DB_PASS = "opik"
    $env:JAVA_OPTS = "-Dliquibase.propertySubstitutionEnabled=true -XX:+UseG1GC -XX:MaxRAMPercentage=80.0"
    $env:REDIS_URL = "redis://:opik@localhost:6379/"

    # Run database migrations if --migrate flag is specified
    if ($RUN_MIGRATIONS) {
        Write-StatusInfo "Running database migrations..."
        # Set OPIK_VERSION for the migration script and create symlink
        $jarFile = Get-ChildItem "target\opik-backend-*.jar" | Where-Object { $_.Name -notlike "*sources*" -and $_.Name -notlike "original-*" } | Select-Object -First 1
        $migrationJar = $null
        if ($jarFile) {
            # Extract version from JAR filename (e.g., opik-backend-1.0-SNAPSHOT.jar -> 1.0-SNAPSHOT)
            $env:OPIK_VERSION = $jarFile.BaseName -replace '^opik-backend-', ''
            
            # Create symlink for migration script (it expects JAR in current directory)
            $migrationJar = "opik-backend-$($env:OPIK_VERSION).jar"
            if (-not (Test-Path $migrationJar)) {
                New-Item -ItemType SymbolicLink -Path $migrationJar -Target $jarFile.FullName -Force | Out-Null
            }
        }
        if (Test-Path "run_db_migrations.ps1") {
            & .\run_db_migrations.ps1
        } elseif (Test-Path "run_db_migrations.sh") {
            & bash .\run_db_migrations.sh
        } else {
            Write-StatusWarning "Database migration script not found, skipping..."
        }
        
        # Clean up symlink after migrations
        if ($migrationJar -and (Test-Path $migrationJar) -and ((Get-Item $migrationJar).LinkType -eq "SymbolicLink")) {
            Remove-Item $migrationJar -Force
        }
    } else {
        Write-StatusInfo "Skipping database migrations (use --migrate flag to run them)"
    }

    # Start the backend
    Write-StatusInfo "Starting backend server..."
    # Use wildcard pattern for JAR filename to avoid hardcoding version
    $jarFile = Get-ChildItem "target\opik-backend-*.jar" | Where-Object { $_.Name -notlike "*sources*" -and $_.Name -notlike "original-*" } | Select-Object -First 1
    if (-not $jarFile) {
        Write-StatusError "No backend JAR file found in target directory. Please build the backend first."
        return $false
    }
    Write-StatusInfo "Using JAR file: $($jarFile.FullName)"
    $backendProcess = Start-Process -FilePath "java" -ArgumentList "$env:JAVA_OPTS", "-jar", "$($jarFile.FullName)", "server", "config.yml" -PassThru -NoNewWindow

    # Wait for backend to start
    Write-StatusInfo "Waiting for backend to start..."
    for ($i = 1; $i -le 30; $i++) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8080/health-check" -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                Write-StatusSuccess "Backend is running on http://localhost:8080"
                return $backendProcess
            }
        } catch {
            # Continue waiting
        }
        if ($i -eq 30) {
            Write-StatusError "Backend failed to start after 30 attempts"
            if ($backendProcess -and -not $backendProcess.HasExited) {
                $backendProcess.Kill()
            }
            exit 1
        }
        Start-Sleep -Seconds 2
    }
}

function Start-FrontendLocal {
    Write-StatusInfo "Starting frontend locally..."

    $frontendDir = Join-Path $scriptDir "apps\opik-frontend"
    Set-Location $frontendDir

    # Install dependencies if node_modules doesn't exist
    if (-not (Test-Path "node_modules")) {
        Write-StatusInfo "Installing frontend dependencies..."
        & npm install
    }

    # Frontend environment is already configured in Start-LocalContainers

    # Start the frontend development server
    Write-StatusInfo "Starting frontend development server..."
    $frontendProcess = Start-Process -FilePath "npm" -ArgumentList "start" -PassThru -NoNewWindow

    # Wait for frontend to start
    Write-StatusInfo "Waiting for frontend to start..."
    for ($i = 1; $i -le 30; $i++) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:5174" -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                Write-StatusSuccess "Frontend is running on http://localhost:5174"
                return $frontendProcess
            }
        } catch {
            # Continue waiting
        }
        if ($i -eq 30) {
            Write-StatusError "Frontend failed to start after 30 attempts"
            if ($frontendProcess -and -not $frontendProcess.HasExited) {
                $frontendProcess.Kill()
            }
            exit 1
        }
        Start-Sleep -Seconds 2
    }
}

function Stop-LocalProcesses {
    param(
        [System.Diagnostics.Process]$BackendProcess,
        [System.Diagnostics.Process]$FrontendProcess
    )
    
    Write-StatusInfo "Cleaning up local development environment..."

    if ($BackendProcess -and -not $BackendProcess.HasExited) {
        Write-StatusInfo "Stopping backend..."
        $BackendProcess.Kill()
    }

    if ($FrontendProcess -and -not $FrontendProcess.HasExited) {
        Write-StatusInfo "Stopping frontend..."
        $FrontendProcess.Kill()
    }

    Write-StatusSuccess "Local development cleanup completed"
}

function Start-LocalDevelopment {
    Write-StatusInfo "Starting OPIK local development environment..."

    # Check requirements
    Test-LocalRequirements
    Test-DockerStatus

    # Start containers for local development
    Start-LocalContainers

    # Build and run backend and frontend
    Build-BackendLocal
    $backendProcess = Start-BackendLocal
    $frontendProcess = Start-FrontendLocal

    Write-StatusSuccess "OPIK local development environment is ready!"
    Write-StatusInfo "Backend: http://localhost:8080"
    Write-StatusInfo "Frontend: http://localhost:5174"
    Write-StatusInfo "Press Ctrl+C to stop all services"

    # Set up cleanup on exit
    Register-EngineEvent PowerShell.Exiting -Action {
        Stop-LocalProcesses -BackendProcess $backendProcess -FrontendProcess $frontendProcess
    }

    # Wait for user to stop
    try {
        Write-Host "Press any key to stop all services..."
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    } finally {
        Stop-LocalProcesses -BackendProcess $backendProcess -FrontendProcess $frontendProcess
    }
}

$BUILD_MODE = $false
$DEBUG_MODE = $false
$PORT_MAPPING = $false
$GUARDRAILS_ENABLED = $false
$LOCAL_MODE = $false
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

if ($options -contains '--local') {
    $LOCAL_MODE = $true
    $options = $options | Where-Object { $_ -ne '--local' }
}

if ($options -contains '--migrate') {
    $RUN_MIGRATIONS = $true
    $options = $options | Where-Object { $_ -ne '--migrate' }
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
        # Handle local development mode
        if ($LOCAL_MODE) {
            Start-LocalDevelopment
            exit 0
        }
        
        # Original logic for normal mode
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
