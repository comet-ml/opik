# Opik Development Runner Script

[CmdletBinding()]
param (
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$options = @()
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# Variables
$script:DEBUG_MODE = $false

# Process debug flag
if ($options -contains '--debug') {
    $script:DEBUG_MODE = $true
    $options = $options | Where-Object { $_ -ne '--debug' }
}

# Also check environment variable
if ($env:DEBUG_MODE -eq "true") {
    $script:DEBUG_MODE = $true
}

# Get the main action (first remaining option)
$Action = if ($options.Count -gt 0) { $options[0] } else { '' }

# Reconstruct original command for error messages
$commandArgs = @()
if ($Action) { $commandArgs += $Action }
if ($script:DEBUG_MODE) { $commandArgs += "--debug" }
$script:ORIGINAL_COMMAND = "$PSCommandPath $($commandArgs -join ' ')"

# Configuration
$script:SCRIPT_DIR = Split-Path -Parent $PSCommandPath
$script:PROJECT_ROOT = Split-Path -Parent $SCRIPT_DIR
$script:BACKEND_DIR = Join-Path $PROJECT_ROOT "apps\opik-backend"
$script:FRONTEND_DIR = Join-Path $PROJECT_ROOT "apps\opik-frontend"

# Cross-platform temp directory handling
$script:TEMP_DIR = if ($env:TEMP) { $env:TEMP } elseif ($env:TMPDIR) { $env:TMPDIR } else { "/tmp" }

$script:BACKEND_PID_FILE = Join-Path $script:TEMP_DIR "opik-backend.pid"
$script:FRONTEND_PID_FILE = Join-Path $script:TEMP_DIR "opik-frontend.pid"
$script:BACKEND_LOG_FILE = Join-Path $script:TEMP_DIR "opik-backend.log"
$script:FRONTEND_LOG_FILE = Join-Path $script:TEMP_DIR "opik-frontend.log"

# Logging functions
function Write-LogInfo {
    param([string]$Message)
    Write-Host "[INFO] " -ForegroundColor Blue -NoNewline
    Write-Host $Message
}

function Write-LogSuccess {
    param([string]$Message)
    Write-Host "[SUCCESS] " -ForegroundColor Green -NoNewline
    Write-Host $Message
}

function Write-LogWarning {
    param([string]$Message)
    Write-Host "[WARNING] " -ForegroundColor Yellow -NoNewline
    Write-Host $Message
}

function Write-LogError {
    param([string]$Message)
    Write-Host "[ERROR] " -ForegroundColor Red -NoNewline
    Write-Host $Message
}

function Write-LogDebug {
    param([string]$Message)
    if ($script:DEBUG_MODE) {
        Write-Host "[DEBUG] " -ForegroundColor Yellow -NoNewline
        Write-Host $Message
    }
}

function Test-CommandExists {
    param([string]$Command)
    
    $exists = $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
    if (-not $exists) {
        Write-LogError "Required command '$Command' not found. Please install it."
        exit 1
    }
    return $exists
}

# Function to find JAR files in target directory
function Find-JarFiles {
    $jarFiles = Get-ChildItem -Path "$script:BACKEND_DIR\target" -Filter "opik-backend-*.jar" -File -ErrorAction SilentlyContinue | 
        Where-Object { $_.Name -notmatch "original" -and $_.Name -notmatch "sources" -and $_.Name -notmatch "javadoc" }
    
    if ($jarFiles.Count -eq 0) {
        return $null
    }
    elseif ($jarFiles.Count -eq 1) {
        $script:JAR_FILE = $jarFiles[0].FullName
        Write-LogInfo "Using JAR file: $script:JAR_FILE"
    }
    else {
        Write-LogWarning "Multiple backend JAR files found in target\:"
        foreach ($jar in $jarFiles) {
            Write-LogWarning "  - $($jar.Name)"
        }
        
        # Sort JAR files by version (using natural sort)
        $script:JAR_FILE = ($jarFiles | Sort-Object { [regex]::Replace($_.Name, '\d+', { $args[0].Value.PadLeft(20) }) } | Select-Object -Last 1).FullName
        Write-LogWarning "Automatically selected JAR with highest version: $script:JAR_FILE"
        Write-LogWarning "To use a different JAR, clean up target\ directory and rebuild"
    }
    
    return $script:JAR_FILE
}

# Function to start Docker services
function Start-DockerServices {
    param([string]$Mode)
    
    Write-LogInfo "Starting Docker services..."
    Push-Location $script:PROJECT_ROOT
    
    try {
        # Convert opik.sh call to opik.ps1 equivalent
        $opikScript = Join-Path $script:PROJECT_ROOT "opik.ps1"
        
        if (Test-Path $opikScript) {
            # Use pwsh -File to properly invoke the script with $MyInvocation context
            pwsh -File $opikScript $Mode --port-mapping
            if ($LASTEXITCODE -eq 0) {
                Write-LogSuccess "Docker services started successfully"
            }
            else {
                Write-LogError "Failed to start Docker services"
                exit 1
            }
        }
        else {
            Write-LogError "opik.ps1 script not found at: $opikScript"
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to stop Docker services
function Stop-DockerServices {
    param([string]$Mode)
    
    Write-LogInfo "Stopping Docker services..."
    Push-Location $script:PROJECT_ROOT
    
    try {
        $opikScript = Join-Path $script:PROJECT_ROOT "opik.ps1"
        
        if (Test-Path $opikScript) {
            # Use pwsh -File to properly invoke the script with $MyInvocation context
            pwsh -File $opikScript $Mode --stop
            if ($LASTEXITCODE -eq 0) {
                Write-LogSuccess "Docker services stopped"
            }
            else {
                Write-LogWarning "Failed to stop some Docker services"
            }
        }
    }
    finally {
        Pop-Location
    }
}

# Function to verify Docker services
function Test-DockerServices {
    param([string]$Mode)
    
    Push-Location $script:PROJECT_ROOT
    
    try {
        $opikScript = Join-Path $script:PROJECT_ROOT "opik.ps1"
        
        if (Test-Path $opikScript) {
            # Use pwsh -File to properly invoke the script with $MyInvocation context
            pwsh -File $opikScript $Mode --verify 2>&1 | Out-Null
            return $LASTEXITCODE -eq 0
        }
        return $false
    }
    finally {
        Pop-Location
    }
}

# Wrapper functions for backward compatibility
function Start-Infrastructure {
    Start-DockerServices -Mode "--infra"
}

function Stop-Infrastructure {
    Stop-DockerServices -Mode "--infra"
}

function Test-Infrastructure {
    return Test-DockerServices -Mode "--infra"
}

function Start-LocalBeDockerServices {
    Start-DockerServices -Mode "--local-be"
}

function Stop-LocalBeDockerServices {
    Stop-DockerServices -Mode "--local-be"
}

function Test-LocalBeDockerServices {
    return Test-DockerServices -Mode "--local-be"
}

# Function to build backend
function Build-Backend {
    Test-CommandExists "mvn"
    Write-LogInfo "Building backend (skipping tests)..."
    Write-LogDebug "Backend directory: $script:BACKEND_DIR"
    
    Push-Location $script:BACKEND_DIR
    
    try {
        # Build Maven arguments as an array to avoid parsing issues with dots in property names
        $mavenArgs = @(
            "clean",
            "install",
            "-T", "1C",
            "-Dmaven.test.skip=true",
            "-Dspotless.skip=true",
            "-Dmaven.javadoc.skip=true",
            "-Dmaven.source.skip=true",
            "-Dmaven.test.compile.skip=true",
            "-Dmaven.test.resources.skip=true",
            "-Dmaven.compiler.useIncrementalCompilation=false",
            "-Dresolve.skip=true"
        )
        
        Write-LogDebug "Running: mvn $($mavenArgs -join ' ')"
        
        & mvn @mavenArgs
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Backend build completed successfully"
        }
        else {
            Write-LogError "Backend build failed"
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to build frontend
function Build-Frontend {
    Test-CommandExists "npm"
    Write-LogInfo "Building frontend..."
    
    Push-Location $script:FRONTEND_DIR
    
    try {
        npm install
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Frontend build completed successfully"
        }
        else {
            Write-LogError "Frontend build failed"
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to lint frontend
function Invoke-FrontendLint {
    Test-CommandExists "npm"
    Write-LogInfo "Linting frontend..."
    
    Push-Location $script:FRONTEND_DIR
    
    try {
        npm run lint
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Frontend linting completed successfully"
        }
        else {
            Write-LogError "Frontend linting failed"
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to lint backend
function Invoke-BackendLint {
    Test-CommandExists "mvn"
    Write-LogInfo "Linting backend..."
    
    Push-Location $script:BACKEND_DIR
    
    try {
        mvn spotless:apply
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Backend linting completed successfully"
        }
        else {
            Write-LogError "Backend linting failed"
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to print migrations recovery message
function Write-MigrationsRecoveryMessage {
    Write-LogError "To recover, you may need to clean up Docker volumes (WARNING: ALL DATA WILL BE LOST):"
    Write-LogError "  1. Stop all services: $PSCommandPath --stop"
    Write-LogError "  2. Remove Docker volumes (DANGER): docker volume prune -a -f"
    Write-LogError "  3. Run again your current flow: $script:ORIGINAL_COMMAND"
}

# Function to run database migrations
function Invoke-DbMigrations {
    Test-CommandExists "java"
    Write-LogInfo "Running database migrations..."
    Write-LogDebug "Backend directory: $script:BACKEND_DIR"
    
    Push-Location $script:BACKEND_DIR
    
    try {
        # Find and validate the JAR file
        $jarFile = Find-JarFiles
        if (-not $jarFile) {
            Write-LogWarning "No backend JAR file found in target\. Building backend automatically..."
            Build-Backend
            
            # Re-scan for JAR files after build
            $jarFile = Find-JarFiles
            if (-not $jarFile) {
                Write-LogError "Backend build completed but no JAR file found. Build may have failed."
                exit 1
            }
        }
        
        Write-LogDebug "Running migrations with JAR: $jarFile"
        Write-LogDebug "Current directory: $(Get-Location)"
        
        # Run MySQL (state DB) migrations
        Write-LogInfo "Running MySQL (state DB) migrations..."
        $env:STATE_DB_DATABASE_NAME = "opik"
        
        java -jar $jarFile db migrate config.yml
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "MySQL migrations completed successfully"
        }
        else {
            Write-LogError "MySQL migrations failed"
            Write-MigrationsRecoveryMessage
            exit 1
        }
        
        # Run ClickHouse (analytics DB) migrations
        Write-LogInfo "Running ClickHouse (analytics DB) migrations..."
        $env:ANALYTICS_DB_DATABASE_NAME = "opik"
        $env:ANALYTICS_DB_MIGRATIONS_URL = "jdbc:clickhouse://localhost:8123"
        
        java -jar $jarFile dbAnalytics migrate config.yml
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "ClickHouse migrations completed successfully"
        }
        else {
            Write-LogError "ClickHouse migrations failed"
            Write-MigrationsRecoveryMessage
            exit 1
        }
        
        Write-LogSuccess "All database migrations completed successfully"
    }
    finally {
        Pop-Location
    }
}

# Function to start backend
function Start-Backend {
    Test-CommandExists "java"
    Write-LogInfo "Starting backend..."
    Write-LogDebug "Backend directory: $script:BACKEND_DIR"
    
    Push-Location $script:BACKEND_DIR
    
    try {
        # Check if backend is already running
        if (Test-Path $script:BACKEND_PID_FILE) {
            $backendPid = Get-Content $script:BACKEND_PID_FILE
            $process = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
            
            if ($process) {
                Write-LogWarning "Backend is already running (PID: $backendPid)"
                return
            }
            else {
                Write-LogWarning "Removing stale backend PID file (process $backendPid no longer exists)"
                Remove-Item $script:BACKEND_PID_FILE -Force
            }
        }
        
        # Set environment variables
        $env:CORS = "true"
        
        # Set debug logging if debug mode is enabled
        if ($script:DEBUG_MODE) {
            $env:GENERAL_LOG_LEVEL = "DEBUG"
            $env:OPIK_LOG_LEVEL = "DEBUG"
            Write-LogDebug "Debug logging enabled - GENERAL_LOG_LEVEL=DEBUG, OPIK_LOG_LEVEL=DEBUG"
        }
        
        # Find and validate the JAR file
        $jarFile = Find-JarFiles
        if (-not $jarFile) {
            Write-LogWarning "No backend JAR file found in target\. Building backend automatically..."
            Build-Backend
            
            # Re-scan for JAR files after build
            $jarFile = Find-JarFiles
            if (-not $jarFile) {
                Write-LogError "Backend build completed but no JAR file found. Build may have failed."
                exit 1
            }
        }
        
        Write-LogDebug "Starting backend with JAR: $jarFile"
        Write-LogDebug "Command: java -jar $jarFile server config.yml"
        
        # Start backend in background with output redirected to log file
        # Use Start-Process for better cross-platform compatibility
        $processParams = @{
            FilePath = "java"
            ArgumentList = @("-jar", $jarFile, "server", "config.yml")
            WorkingDirectory = $script:BACKEND_DIR
            RedirectStandardOutput = $script:BACKEND_LOG_FILE
            RedirectStandardError = $script:BACKEND_LOG_FILE
            NoNewWindow = $true
            PassThru = $true
        }
        
        $process = Start-Process @processParams
        $backendPid = $process.Id
        Set-Content -Path $script:BACKEND_PID_FILE -Value $backendPid
        
        Write-LogDebug "Backend process started with PID: $backendPid"
        
        # Wait a bit and check if process is still running
        Start-Sleep -Seconds 3
        
        $stillRunning = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
        
        if ($stillRunning) {
            Write-LogSuccess "Backend started successfully (PID: $backendPid)"
            Write-LogInfo "Backend logs: Get-Content -Wait '$script:BACKEND_LOG_FILE'"
            
            if ($script:DEBUG_MODE) {
                Write-LogDebug "Debug mode enabled - check logs for detailed output"
            }
        }
        else {
            Write-LogError "Backend failed to start. Check logs: Get-Content '$script:BACKEND_LOG_FILE'"
            Remove-Item $script:BACKEND_PID_FILE -Force -ErrorAction SilentlyContinue
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to start frontend
function Start-Frontend {
    Test-CommandExists "npm"
    Write-LogInfo "Starting frontend..."
    Write-LogDebug "Frontend directory: $script:FRONTEND_DIR"
    
    Push-Location $script:FRONTEND_DIR
    
    try {
        # Check if frontend is already running
        if (Test-Path $script:FRONTEND_PID_FILE) {
            $frontendPid = Get-Content $script:FRONTEND_PID_FILE
            $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
            
            if ($process) {
                Write-LogWarning "Frontend is already running (PID: $frontendPid)"
                return
            }
            else {
                Write-LogWarning "Removing stale frontend PID file (process $frontendPid no longer exists)"
                Remove-Item $script:FRONTEND_PID_FILE -Force
            }
        }
        
        # Set debug logging for frontend if debug mode is enabled
        if ($script:DEBUG_MODE) {
            $env:NODE_ENV = "development"
            Write-LogDebug "Frontend debug mode enabled - NODE_ENV=development"
        }
        
        # Configure frontend to talk to local backend
        $env:VITE_BASE_API_URL = "http://localhost:8080"
        Write-LogInfo "Frontend API base URL (VITE_BASE_API_URL) set to: $env:VITE_BASE_API_URL"
        
        Write-LogDebug "Starting frontend with: npm run start"
        
        # Start frontend in background with output redirected to log file
        # Use Start-Process for better cross-platform compatibility
        $env:CI = "true"
        
        $processParams = @{
            FilePath = "npm"
            ArgumentList = @("run", "start")
            WorkingDirectory = $script:FRONTEND_DIR
            RedirectStandardOutput = $script:FRONTEND_LOG_FILE
            RedirectStandardError = $script:FRONTEND_LOG_FILE
            NoNewWindow = $true
            PassThru = $true
        }
        
        $process = Start-Process @processParams
        $frontendPid = $process.Id
        Set-Content -Path $script:FRONTEND_PID_FILE -Value $frontendPid
        
        Write-LogDebug "Frontend process started with PID: $frontendPid"
        
        # Wait a bit and check if process is still running
        Start-Sleep -Seconds 3
        
        $stillRunning = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
        
        if ($stillRunning) {
            Write-LogSuccess "Frontend started successfully (PID: $frontendPid)"
            Write-LogInfo "Frontend logs: Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
        }
        else {
            Write-LogError "Frontend failed to start. Check logs: Get-Content '$script:FRONTEND_LOG_FILE'"
            Remove-Item $script:FRONTEND_PID_FILE -Force -ErrorAction SilentlyContinue
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to stop backend
function Stop-Backend {
    if (Test-Path $script:BACKEND_PID_FILE) {
        $backendPid = Get-Content $script:BACKEND_PID_FILE
        $process = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
        
        if ($process) {
            Write-LogInfo "Stopping backend (PID: $backendPid)..."
            
            # Try graceful shutdown first
            Stop-Process -Id $backendPid -ErrorAction SilentlyContinue
            
            # Wait for graceful shutdown
            $timeout = 10
            for ($i = 0; $i -lt $timeout; $i++) {
                $process = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
                if (-not $process) {
                    break
                }
                Start-Sleep -Seconds 1
            }
            
            # Force kill if still running
            $process = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
            if ($process) {
                Write-LogWarning "Force killing backend..."
                Stop-Process -Id $backendPid -Force -ErrorAction SilentlyContinue
            }
            
            Write-LogSuccess "Backend stopped"
        }
        else {
            Write-LogWarning "Backend PID file exists but process is not running (cleaning up stale PID file)"
        }
        
        Remove-Item $script:BACKEND_PID_FILE -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-LogWarning "Backend is not running"
    }
}

# Function to stop frontend
function Stop-Frontend {
    if (Test-Path $script:FRONTEND_PID_FILE) {
        $frontendPid = Get-Content $script:FRONTEND_PID_FILE
        $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
        
        if ($process) {
            Write-LogInfo "Stopping frontend (PID: $frontendPid)..."
            
            # Get all child processes before killing parent
            $childProcesses = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $frontendPid }
            
            # Try graceful shutdown first
            Stop-Process -Id $frontendPid -ErrorAction SilentlyContinue
            
            # Wait for graceful shutdown
            $timeout = 10
            for ($i = 0; $i -lt $timeout; $i++) {
                $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
                if (-not $process) {
                    break
                }
                Start-Sleep -Seconds 1
            }
            
            # Force kill if still running
            $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
            if ($process) {
                Write-LogWarning "Force killing frontend..."
                Stop-Process -Id $frontendPid -Force -ErrorAction SilentlyContinue
                
                # Kill child processes
                foreach ($childProc in $childProcesses) {
                    $childId = $childProc.ProcessId
                    $childProcess = Get-Process -Id $childId -ErrorAction SilentlyContinue
                    if ($childProcess) {
                        Write-LogWarning "Killing child process (PID: $childId)..."
                        Stop-Process -Id $childId -Force -ErrorAction SilentlyContinue
                    }
                }
            }
            
            Write-LogSuccess "Frontend stopped"
        }
        else {
            Write-LogWarning "Frontend PID file exists but process is not running (cleaning up stale PID file)"
        }
        
        Remove-Item $script:FRONTEND_PID_FILE -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-LogWarning "Frontend is not running"
    }
    
    # Clean up any orphaned npm/node/vite processes related to frontend directory
    $orphanedProcesses = Get-Process -Name node, npm, vite -ErrorAction SilentlyContinue | 
        Where-Object { $_.Path -like "*$($script:FRONTEND_DIR)*" -or $_.CommandLine -like "*$($script:FRONTEND_DIR)*" }
    
    if ($orphanedProcesses) {
        Write-LogWarning "Found potential orphaned processes related to $script:FRONTEND_DIR..."
        foreach ($proc in $orphanedProcesses) {
            Write-LogWarning "Cleaning up orphaned process: PID $($proc.Id) - $($proc.Name)"
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

# Helper function to display backend process status
function Get-BackendStatus {
    if ((Test-Path $script:BACKEND_PID_FILE) -and (Get-Process -Id (Get-Content $script:BACKEND_PID_FILE) -ErrorAction SilentlyContinue)) {
        $pid = Get-Content $script:BACKEND_PID_FILE
        Write-Host "Backend: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green -NoNewline
        Write-Host " (PID: $pid)"
        return $true
    }
    else {
        Write-Host "Backend: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
        return $false
    }
}

# Helper function to display access information
function Show-AccessInformation {
    param(
        [string]$UiUrl,
        [bool]$ShowManualEdit = $true
    )
    
    Write-Host ""
    Write-Host "🚀 Opik Development Environment is Ready!" -ForegroundColor Green
    Write-Host "📊  Access the UI:     $UiUrl" -ForegroundColor Blue
    Write-Host "🛠️  API ping Endpoint: http://localhost:8080/is-alive/ping" -ForegroundColor Blue
    Write-Host ""
    Write-Host "ℹ️  SDK Configuration Required:" -ForegroundColor Blue
    Write-Host "To use the Opik SDK with your local development environment, you MUST configure it to point to your local instance."
    Write-Host ""
    Write-Host "Run SDK Configuration Command:" -ForegroundColor Blue
    Write-Host "  opik configure --use_local"
    
    if ($ShowManualEdit) {
        Write-Host "  # When prompted:"
        Write-Host "  #   - Choose 'Local deployment' option"
        Write-Host "  #   - Enter URL: http://localhost:8080"
        Write-Host ""
        Write-Host "⚠️  IMPORTANT: Manual Configuration File Edit Required!" -ForegroundColor Yellow
        Write-Host "After running 'opik configure', you MUST manually edit the configuration file to remove '/api' from the URL."
        Write-Host ""
        Write-Host "Edit the configuration file:" -ForegroundColor Blue
        Write-Host "  # Open the configuration file, by default: ~/.opik.config"
        Write-Host ""
        Write-Host "  # Change this line:"
        Write-Host "  url_override = http://localhost:8080/api/"
        Write-Host ""
        Write-Host "  # To this (remove '/api'):"
        Write-Host "  url_override = http://localhost:8080"
    }
    else {
        Write-Host "  # When prompted, use URL: $UiUrl"
    }
    
    Write-Host ""
    Write-Host "Alternative - Environment Variables:" -ForegroundColor Blue
    
    if ($ShowManualEdit) {
        Write-Host "  `$env:OPIK_URL_OVERRIDE = 'http://localhost:8080'"
    }
    else {
        Write-Host "  `$env:OPIK_URL_OVERRIDE = '$UiUrl/api'"
    }
    
    Write-Host "  `$env:OPIK_WORKSPACE = 'default'"
    Write-Host ""
    Write-Host "Important Notes:" -ForegroundColor Yellow
    Write-Host "  • The configuration file is located at ~/.opik.config by default"
    
    if ($ShowManualEdit) {
        Write-Host "  • You MUST remove '/api' from the URL for local development"
    }
    
    Write-Host "  • Default workspace is 'default'"
    Write-Host "  • No API key required for local instances"
    Write-Host ""
    Write-Host "📖 For complete configuration documentation, visit:" -ForegroundColor Blue
    Write-Host "   https://www.comet.com/docs/opik/tracing/sdk_configuration"
}

# Function to verify services
function Test-Services {
    Write-LogInfo "=== Opik Development Status ==="
    
    # Infrastructure status
    $infraRunning = Test-Infrastructure
    if ($infraRunning) {
        Write-Host "Infrastructure: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green -NoNewline
        Write-Host " (Docker containers)"
    }
    else {
        Write-Host "Infrastructure: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red -NoNewline
        Write-Host " (Docker containers)"
    }
    
    # Backend status
    $backendRunning = Get-BackendStatus
    
    # Frontend status
    $frontendRunning = $false
    if ((Test-Path $script:FRONTEND_PID_FILE) -and (Get-Process -Id (Get-Content $script:FRONTEND_PID_FILE) -ErrorAction SilentlyContinue)) {
        $pid = Get-Content $script:FRONTEND_PID_FILE
        Write-Host "Frontend: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green -NoNewline
        Write-Host " (PID: $pid)"
        $frontendRunning = $true
    }
    else {
        Write-Host "Frontend: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # Show access information if all services are running
    if ($infraRunning -and $backendRunning -and $frontendRunning) {
        Show-AccessInformation -UiUrl "http://localhost:5174" -ShowManualEdit $true
    }
    
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  Backend:  Get-Content -Wait '$script:BACKEND_LOG_FILE'"
    Write-Host "  Frontend: Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
}

# Function to verify BE-only services
function Test-BeOnlyServices {
    Write-LogInfo "=== Opik BE-Only Development Status ==="
    
    # Infrastructure and Docker Frontend status
    $dockerServicesRunning = Test-LocalBeDockerServices
    if ($dockerServicesRunning) {
        Write-Host "Infrastructure + Docker Frontend: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green -NoNewline
        Write-Host " (Docker containers)"
    }
    else {
        Write-Host "Infrastructure + Docker Frontend: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red -NoNewline
        Write-Host " (Docker containers)"
    }
    
    # Backend process status
    $backendRunning = Get-BackendStatus
    
    # Show access information if all services are running
    if ($dockerServicesRunning -and $backendRunning) {
        Show-AccessInformation -UiUrl "http://localhost:5173" -ShowManualEdit $false
    }
    
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  Backend Process: Get-Content -Wait '$script:BACKEND_LOG_FILE'"
    Write-Host "  Docker Services: docker logs -f opik-frontend-1"
}

# Function to start services (without building)
function Start-Services {
    Write-LogInfo "=== Starting Opik Development Environment ==="
    Write-LogWarning "=== Not rebuilding: the latest local changes may not be reflected ==="
    Write-LogInfo "Step 1/4: Starting infrastructure..."
    Start-Infrastructure
    Write-LogInfo "Step 2/4: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 3/4: Starting backend..."
    Start-Backend
    Write-LogInfo "Step 4/4: Starting frontend..."
    Start-Frontend
    Write-LogSuccess "=== Start Complete ==="
    Test-Services
}

# Function to stop services
function Stop-Services {
    Write-LogInfo "=== Stopping Opik Development Environment ==="
    Write-LogInfo "Step 1/3: Stopping frontend..."
    Stop-Frontend
    Write-LogInfo "Step 2/3: Stopping backend..."
    Stop-Backend
    Write-LogInfo "Step 3/3: Stopping infrastructure..."
    Stop-Infrastructure
    Write-LogSuccess "=== Stop Complete ==="
}

# Function to run migrations
function Invoke-Migrations {
    Write-LogInfo "=== Running Database Migrations ==="
    Write-LogInfo "Step 1/3: Starting infrastructure..."
    Start-Infrastructure
    Write-LogInfo "Step 2/3: Building backend..."
    Build-Backend
    Write-LogInfo "Step 3/3: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogSuccess "=== Migrations Complete ==="
}

# Function to restart services (stop, build, start)
function Restart-Services {
    Write-LogInfo "=== Restarting Opik Development Environment ==="
    Write-LogInfo "Step 1/9: Stopping frontend..."
    Stop-Frontend
    Write-LogInfo "Step 2/9: Stopping backend..."
    Stop-Backend
    Write-LogInfo "Step 3/9: Stopping infrastructure..."
    Stop-Infrastructure
    Write-LogInfo "Step 4/9: Starting infrastructure..."
    Start-Infrastructure
    Write-LogInfo "Step 5/9: Building backend..."
    Build-Backend
    Write-LogInfo "Step 6/9: Building frontend..."
    Build-Frontend
    Write-LogInfo "Step 7/9: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 8/9: Starting backend..."
    Start-Backend
    Write-LogInfo "Step 9/9: Starting frontend..."
    Start-Frontend
    Write-LogSuccess "=== Restart Complete ==="
    Test-Services
}

# Function to start BE-only services (without building)
function Start-BeOnlyServices {
    Write-LogInfo "=== Starting Opik BE-Only Development Environment ==="
    Write-LogWarning "=== Not rebuilding: the latest local changes may not be reflected ==="
    Write-LogInfo "Step 1/3: Starting infrastructure and Docker frontend..."
    Start-LocalBeDockerServices
    Write-LogInfo "Step 2/3: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 3/3: Starting backend process..."
    Start-Backend
    Write-LogSuccess "=== BE-Only Start Complete ==="
    Test-BeOnlyServices
}

# Function to stop BE-only services
function Stop-BeOnlyServices {
    Write-LogInfo "=== Stopping Opik BE-Only Development Environment ==="
    Write-LogInfo "Step 1/2: Stopping backend process..."
    Stop-Backend
    Write-LogInfo "Step 2/2: Stopping infrastructure and Docker frontend..."
    Stop-LocalBeDockerServices
    Write-LogSuccess "=== BE-Only Stop Complete ==="
}

# Function to restart BE-only services (stop, build, start)
function Restart-BeOnlyServices {
    Write-LogInfo "=== Restarting Opik BE-Only Development Environment ==="
    Write-LogInfo "Step 1/6: Stopping backend process..."
    Stop-Backend
    Write-LogInfo "Step 2/6: Stopping infrastructure and Docker frontend..."
    Stop-LocalBeDockerServices
    Write-LogInfo "Step 3/6: Starting infrastructure and Docker frontend..."
    Start-LocalBeDockerServices
    Write-LogInfo "Step 4/6: Building backend..."
    Build-Backend
    Write-LogInfo "Step 5/6: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 6/6: Starting backend process..."
    Start-Backend
    Write-LogSuccess "=== BE-Only Restart Complete ==="
    Test-BeOnlyServices
}

# Function to show logs
function Show-Logs {
    Write-LogInfo "=== Recent Logs ==="
    
    if (Test-Path $script:BACKEND_LOG_FILE) {
        Write-Host "`nBackend logs (last 20 lines):" -ForegroundColor Blue
        Get-Content $script:BACKEND_LOG_FILE -Tail 20
    }
    
    if (Test-Path $script:FRONTEND_LOG_FILE) {
        Write-Host "`nFrontend logs (last 20 lines):" -ForegroundColor Blue
        Get-Content $script:FRONTEND_LOG_FILE -Tail 20
    }
    
    Write-Host "`nTo follow logs in real-time:" -ForegroundColor Blue
    Write-Host "  Backend:  Get-Content -Wait '$script:BACKEND_LOG_FILE'"
    Write-Host "  Frontend: Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
}

# Function to show usage
function Show-Usage {
    Write-Host "Usage: $PSCommandPath [OPTIONS]"
    Write-Host ""
    Write-Host "Standard Mode (BE and FE services as processes):"
    Write-Host "  --start        - Start Docker infrastructure, and BE and FE processes (without building)"
    Write-Host "  --stop         - Stop Docker infrastructure, and BE and FE processes"
    Write-Host "  --restart      - Stop, build, and start Docker infrastructure, and BE and FE processes (DEFAULT IF NO OPTIONS PROVIDED)"
    Write-Host "  --verify       - Verify status of Docker infrastructure, and BE and FE processes"
    Write-Host ""
    Write-Host "BE-Only Mode (BE as process, FE in Docker):"
    Write-Host "  --be-only-start    - Start Docker infrastructure and FE, and backend process (without building)"
    Write-Host "  --be-only-stop     - Stop Docker infrastructure and FE, and backend process"
    Write-Host "  --be-only-restart  - Stop, build, and Docker infrastructure and FE, and backend process"
    Write-Host "  --be-only-verify   - Verify status of Docker infrastructure and FE, and backend process"
    Write-Host ""
    Write-Host "Other options:"
    Write-Host "  --build-be     - Build backend"
    Write-Host "  --build-fe     - Build frontend"
    Write-Host "  --migrate      - Run database migrations"
    Write-Host "  --lint-be      - Lint backend code"
    Write-Host "  --lint-fe      - Lint frontend code"
    Write-Host "  --debug        - Enable debug mode (meant to be combined with other flags)"
    Write-Host "  --logs         - Show logs for backend and frontend services"
    Write-Host "  --help         - Show this help message"
    Write-Host ""
    Write-Host "Environment Variables:"
    Write-Host "  `$env:DEBUG_MODE = `"true`"  - Enable debug mode"
}

# Show debug mode status
if ($script:DEBUG_MODE) {
    Write-LogDebug "Debug mode is ENABLED"
}

# Main script logic
switch ($Action.ToLower()) {
    "--build-be" {
        Build-Backend
    }
    "--build-fe" {
        Build-Frontend
    }
    "--migrate" {
        Invoke-Migrations
    }
    "--start" {
        Start-Services
    }
    "--stop" {
        Stop-Services
    }
    "--restart" {
        Restart-Services
    }
    "--verify" {
        Test-Services
    }
    "--be-only-start" {
        Start-BeOnlyServices
    }
    "--be-only-stop" {
        Stop-BeOnlyServices
    }
    "--be-only-restart" {
        Restart-BeOnlyServices
    }
    "--be-only-verify" {
        Test-BeOnlyServices
    }
    "--logs" {
        Show-Logs
    }
    "--lint-fe" {
        Invoke-FrontendLint
    }
    "--lint-be" {
        Invoke-BackendLint
    }
    "--help" {
        Show-Usage
    }
    "" {
        # Default action: restart
        Restart-Services
    }
    default {
        Write-LogError "Unknown option: $Action"
        Write-Host ""
        Show-Usage
        exit 1
    }
}

