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
$script:BACKEND_DIR = Join-Path (Join-Path $PROJECT_ROOT "apps") "opik-backend"
$script:FRONTEND_DIR = Join-Path (Join-Path $PROJECT_ROOT "apps") "opik-frontend"
$script:AI_BACKEND_DIR = Join-Path (Join-Path $PROJECT_ROOT "apps") "opik-ai-backend"

# Cross-platform temp directory handling
$script:TEMP_DIR = if ($env:TEMP) { 
    $env:TEMP 
} elseif ($env:TMPDIR) { 
    $env:TMPDIR 
} else { 
    [System.IO.Path]::GetTempPath().TrimEnd([System.IO.Path]::DirectorySeparatorChar)
}

$script:BACKEND_PID_FILE = Join-Path $script:TEMP_DIR "opik-backend.pid"
$script:FRONTEND_PID_FILE = Join-Path $script:TEMP_DIR "opik-frontend.pid"
$script:AI_BACKEND_PID_FILE = Join-Path $script:TEMP_DIR "opik-ai-backend.pid"
$script:BACKEND_LOG_FILE = Join-Path $script:TEMP_DIR "opik-backend.log"
$script:FRONTEND_LOG_FILE = Join-Path $script:TEMP_DIR "opik-frontend.log"
$script:AI_BACKEND_LOG_FILE = Join-Path $script:TEMP_DIR "opik-ai-backend.log"

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

# Helper function to get home directory path (cross-platform)
function Get-HomeDirectory {
    # Use $HOME automatic variable as final fallback (always set by PowerShell)
    if ($env:USERPROFILE) { 
        return $env:USERPROFILE 
    } elseif ($env:HOME) { 
        return $env:HOME 
    } else { 
        return $HOME  # PowerShell automatic variable (not environment variable)
    }
}

function Test-CommandExists {
    param([string]$Command)
    
    $exists = $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
    if (-not $exists) {
        Write-LogError "Required command '$Command' not found. Please install it."
        exit 1
    }
}

# Function to find JAR files in target directory
function Find-JarFiles {
    $targetDir = Join-Path $script:BACKEND_DIR "target"
    $jarFiles = Get-ChildItem -Path $targetDir -Filter "opik-backend-*.jar" -File -ErrorAction SilentlyContinue | 
        Where-Object { $_.Name -notmatch "original" -and $_.Name -notmatch "sources" -and $_.Name -notmatch "javadoc" }
    
    if ($jarFiles.Count -eq 0) {
        return $null
    }
    elseif ($jarFiles.Count -eq 1) {
        $script:JAR_FILE = $jarFiles[0].FullName
        Write-LogInfo "Using JAR file: $script:JAR_FILE"
    }
    else {
        Write-LogWarning "Multiple backend JAR files found in target directory:"
        foreach ($jar in $jarFiles) {
            Write-LogWarning "  - $($jar.Name)"
        }
        
        # Sort JAR files by version (using natural sort)
        $script:JAR_FILE = ($jarFiles | Sort-Object { [regex]::Replace($_.Name, '\d+', { $args[0].Value.PadLeft(20) }) } | Select-Object -Last 1).FullName
        Write-LogWarning "Automatically selected JAR with highest version: $script:JAR_FILE"
        Write-LogWarning "To use a different JAR, clean up target directory and rebuild"
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
            # Invoke opik.ps1 script directly
            & $opikScript $Mode
            if ($?) {
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
            # Invoke opik.ps1 script directly
            & $opikScript $Mode --stop
            if ($?) {
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
            # Invoke opik.ps1 script directly
            & $opikScript $Mode --verify 2>&1 | Out-Null
            return $?
        }
        return $false
    }
    finally {
        Pop-Location
    }
}

function Start-LocalBeFe {
    Start-DockerServices -Mode "--local-be-fe"
}

function Stop-LocalBeFe {
    Stop-DockerServices -Mode "--local-be-fe"
}

function Test-LocalBeFe {
    return Test-DockerServices -Mode "--local-be-fe"
}

function Start-LocalBe {
    Start-DockerServices -Mode "--local-be"
}

function Stop-LocalBe {
    Stop-DockerServices -Mode "--local-be"
}

function Test-LocalBe {
    return Test-DockerServices -Mode "--local-be"
}

function Start-LocalAi {
    Start-DockerServices -Mode "--local-ai"
}

function Stop-LocalAi {
    Stop-DockerServices -Mode "--local-ai"
}

function Test-LocalAi {
    return Test-DockerServices -Mode "--local-ai"
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

# Function to build AI backend
function Build-AiBackend {
    Test-CommandExists "uv"
    Write-LogInfo "Building AI backend..."
    Write-LogDebug "AI backend directory: $script:AI_BACKEND_DIR"
    
    Push-Location $script:AI_BACKEND_DIR
    
    try {
        uv sync
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "AI backend build completed successfully"
        }
        else {
            Write-LogError "AI backend build failed"
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
        npm run lint:fix
        
        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Frontend linting completed successfully"
        }
        else {
            Write-LogError "Frontend linting failed"
            exit 1
        }
        
        Write-LogInfo "Typechecking frontend..."
        npm run typecheck

        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Frontend typechecking completed successfully"
        }
        else {
            Write-LogError "Frontend typechecking failed"
            exit 1
        }

        Write-LogInfo "Validating frontend dependencies..."
        npm run deps:validate

        if ($LASTEXITCODE -eq 0) {
            Write-LogSuccess "Frontend dependency validation completed successfully"
        }
        else {
            Write-LogError "Frontend dependency validation failed"
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
    Write-LogError "  2. Clean all data volumes (DANGER): cd $script:PROJECT_ROOT && .\opik.ps1 --clean"
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
            Write-LogWarning "No backend JAR file found in target directory. Building backend automatically..."
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

function Wait-BackendReady {
    Test-CommandExists "curl"
    Write-LogInfo "Waiting for backend to be ready..."
    $maxWait = 60
    $count = 0
    $backendReady = $false
    
    while ($count -lt $maxWait) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8080/health-check" -Method Get -UseBasicParsing -ErrorAction SilentlyContinue
            if ($response.StatusCode -eq 200) {
                $backendReady = $true
                break
            }
        }
        catch {
            # Continue waiting
        }
        
        Start-Sleep -Seconds 1
        $count++
        
        # Check if backend process is still running
        if (Test-Path $script:BACKEND_PID_FILE) {
            $backendPid = Get-Content $script:BACKEND_PID_FILE
            $process = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
            
            if (-not $process) {
                Write-LogError "Backend process died while waiting for it to be ready"
                Write-LogError "Check logs:"
                Write-LogError "  Get-Content '$script:BACKEND_LOG_FILE'"
                Write-LogError "  Get-Content '$script:BACKEND_LOG_FILE.err'"
                Remove-Item $script:BACKEND_PID_FILE -Force -ErrorAction SilentlyContinue
                return $false
            }
        }
    }
    
    if ($backendReady) {
        Write-LogSuccess "Backend is ready and accepting connections"
        if ($script:DEBUG_MODE) {
            Write-LogDebug "Debug mode enabled - check logs for detailed output"
        }
        return $true
    }
    else {
        Write-LogError "Backend failed to become ready after ${maxWait}s"
        Write-LogError "Check logs:"
        Write-LogError "  Get-Content '$script:BACKEND_LOG_FILE'"
        Write-LogError "  Get-Content '$script:BACKEND_LOG_FILE.err'"
        return $false
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
        
        # Set environment variables for the process
        if ($script:DEBUG_MODE) {
            Write-LogDebug "Debug logging enabled - GENERAL_LOG_LEVEL=DEBUG, OPIK_LOG_LEVEL=DEBUG"
        }
        
        # Find and validate the JAR file
        $jarFile = Find-JarFiles
        if (-not $jarFile) {
            Write-LogWarning "No backend JAR file found in target directory. Building backend automatically..."
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
        
        # Set environment variables for backend
        $env:CORS = "true"
        if ($script:DEBUG_MODE) {
            $env:GENERAL_LOG_LEVEL = "DEBUG"
            $env:OPIK_LOG_LEVEL = "DEBUG"
        }
        
        # Start backend in background using Start-Process
        # Use separate files for stdout and stderr
        $stdoutLog = "$script:BACKEND_LOG_FILE"
        $stderrLog = "$script:BACKEND_LOG_FILE.err"
        
        $processParams = @{
            FilePath = "java"
            ArgumentList = @("-jar", $jarFile, "server", "config.yml")
            WorkingDirectory = $script:BACKEND_DIR
            RedirectStandardOutput = $stdoutLog
            RedirectStandardError = $stderrLog
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
            Write-LogSuccess "Backend process started (PID: $backendPid)"
            Write-LogInfo "Backend logs:"
            Write-LogInfo "  stdout: Get-Content -Wait '$script:BACKEND_LOG_FILE'"
            Write-LogInfo "  stderr: Get-Content -Wait '$script:BACKEND_LOG_FILE.err'"
            
            if (-not (Wait-BackendReady)) {
                exit 1
            }
        }
        else {
            Write-LogError "Backend failed to start. Check logs:"
            Write-LogError "  Get-Content '$script:BACKEND_LOG_FILE'"
            Write-LogError "  Get-Content '$script:BACKEND_LOG_FILE.err'"
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
        
        # Configure frontend API base URL (defaults to /api in frontend code if not set)
        # The Vite dev server proxy will forward /api/* requests to the backend
        if (-not $env:VITE_BASE_API_URL) {
            Write-LogDebug "Frontend API base URL (VITE_BASE_API_URL) not set, will use default from frontend code: /api"
        } else {
            Write-LogInfo "Frontend API base URL (VITE_BASE_API_URL) set to: $env:VITE_BASE_API_URL"
        }
        
        # Set environment variables for frontend
        $env:CI = "true"
        if ($script:DEBUG_MODE) {
            $env:NODE_ENV = "development"
            Write-LogDebug "Frontend debug mode enabled - NODE_ENV=development"
        }
        
        Write-LogDebug "Starting frontend with: npm run start"
        
        # Start frontend in background using Start-Process
        # Use separate files for stdout and stderr
        $stdoutLog = "$script:FRONTEND_LOG_FILE"
        $stderrLog = "$script:FRONTEND_LOG_FILE.err"
        
        $processParams = @{
            FilePath = "npm"
            ArgumentList = @("run", "start")
            WorkingDirectory = $script:FRONTEND_DIR
            RedirectStandardOutput = $stdoutLog
            RedirectStandardError = $stderrLog
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
            Write-LogInfo "Frontend logs:"
            Write-LogInfo "  stdout: Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
            Write-LogInfo "  stderr: Get-Content -Wait '$script:FRONTEND_LOG_FILE.err'"
        }
        else {
            Write-LogError "Frontend failed to start. Check logs:"
            Write-LogError "  Get-Content '$script:FRONTEND_LOG_FILE'"
            Write-LogError "  Get-Content '$script:FRONTEND_LOG_FILE.err'"
            Remove-Item $script:FRONTEND_PID_FILE -Force -ErrorAction SilentlyContinue
            exit 1
        }
    }
    finally {
        Pop-Location
    }
}

# Function to wait for AI backend to be ready
function Wait-AiBackendReady {
    Test-CommandExists "curl"
    Write-LogInfo "Waiting for AI backend to be ready on port 8081..."
    $maxWait = 60
    $count = 0
    $aiBackendReady = $false
    
    while ($count -lt $maxWait) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8081/opik-ai/healthz" -Method Get -UseBasicParsing -ErrorAction SilentlyContinue
            if ($response.StatusCode -eq 200) {
                $aiBackendReady = $true
                break
            }
        }
        catch {
            # Continue waiting
        }
        
        Start-Sleep -Seconds 1
        $count++
        
        # Check if AI backend process is still running
        if (Test-Path $script:AI_BACKEND_PID_FILE) {
            $aiBackendPid = Get-Content $script:AI_BACKEND_PID_FILE
            $process = Get-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
            
            if (-not $process) {
                Write-LogError "AI backend process died while waiting for it to be ready"
                Write-LogError "Check logs: Get-Content '$script:AI_BACKEND_LOG_FILE'"
                Remove-Item $script:AI_BACKEND_PID_FILE -Force -ErrorAction SilentlyContinue
                return $false
            }
        }
    }
    
    if ($aiBackendReady) {
        Write-LogSuccess "AI backend is ready and accepting connections"
        Write-LogInfo "AI backend API: http://localhost:8081"
        if ($script:DEBUG_MODE) {
            Write-LogDebug "Debug mode enabled - check logs for detailed output"
        }
        return $true
    }
    else {
        Write-LogError "AI backend failed to become ready after ${maxWait}s"
        Write-LogError "Check logs: Get-Content '$script:AI_BACKEND_LOG_FILE'"
        return $false
    }
}

# Function to start AI backend
function Start-AiBackend {
    Test-CommandExists "uv"
    Write-LogInfo "Starting AI backend on port 8081..."
    Write-LogDebug "AI backend directory: $script:AI_BACKEND_DIR"
    
    # Verify AI backend directory exists
    if (-not (Test-Path $script:AI_BACKEND_DIR)) {
        Write-LogError "AI backend directory not found: $script:AI_BACKEND_DIR"
        exit 1
    }
    
    # Check if AI backend is already running
    if (Test-Path $script:AI_BACKEND_PID_FILE) {
        $aiBackendPid = Get-Content $script:AI_BACKEND_PID_FILE
        $process = Get-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
        
        if ($process) {
            Write-LogWarning "AI backend is already running (PID: $aiBackendPid)"
            return
        }
        else {
            Write-LogWarning "Removing stale AI backend PID file (process $aiBackendPid no longer exists)"
            Remove-Item $script:AI_BACKEND_PID_FILE -Force
        }
    }
    
    # Check if uv sync has been run
    if (-not (Test-Path (Join-Path $script:AI_BACKEND_DIR ".venv"))) {
        Write-LogWarning "Virtual environment not found. Building AI backend automatically..."
        Build-AiBackend
    }
    
    # Set environment variables for local development
    $env:PORT = "8081"
    $env:URL_PREFIX = "/opik-ai"
    $env:DEVELOPMENT_MODE = "true"
    $env:DEV_USER_ID = "local-dev-user"
    $env:DEV_WORKSPACE_NAME = "default"
    # Use 127.0.0.1 instead of localhost to force TCP connection
    $env:SESSION_SERVICE_URI = "mysql://opik:opik@127.0.0.1:3306/opik"
    $env:AGENT_OPIK_URL = "http://localhost:8080"
    $env:OPIK_URL_OVERRIDE = "http://localhost:8080"
    # Set PYTHONPATH to include the src directory
    $aiBackendSrc = Join-Path $script:AI_BACKEND_DIR "src"
    $env:PYTHONPATH = if ($env:PYTHONPATH) { "$aiBackendSrc;$env:PYTHONPATH" } else { $aiBackendSrc }
    
    Write-LogDebug "AI backend configured with:"
    Write-LogDebug "  PORT=$env:PORT"
    Write-LogDebug "  URL_PREFIX=$env:URL_PREFIX"
    Write-LogDebug "  DEVELOPMENT_MODE=$env:DEVELOPMENT_MODE"
    Write-LogDebug "  SESSION_SERVICE_URI=$env:SESSION_SERVICE_URI"
    Write-LogDebug "  AGENT_OPIK_URL=$env:AGENT_OPIK_URL"
    Write-LogDebug "  OPIK_URL_OVERRIDE=$env:OPIK_URL_OVERRIDE"
    Write-LogDebug "  PYTHONPATH=$env:PYTHONPATH"
    
    # Set debug logging if debug mode is enabled
    if ($script:DEBUG_MODE) {
        Write-LogDebug "Debug logging enabled for AI backend"
    }
    
    Write-LogDebug "Starting AI backend with: uv run --directory $script:AI_BACKEND_DIR uvicorn opik_ai_backend.main:app --host 0.0.0.0 --port 8081 --reload --reload-dir src"
    
    # Start AI backend in background using Start-Process
    $stdoutLog = "$script:AI_BACKEND_LOG_FILE"
    $stderrLog = "$script:AI_BACKEND_LOG_FILE.err"
    
    $processParams = @{
        FilePath = "uv"
        ArgumentList = @("run", "--directory", $script:AI_BACKEND_DIR, "uvicorn", "opik_ai_backend.main:app", "--host", "0.0.0.0", "--port", "8081", "--reload", "--reload-dir", "src")
        WorkingDirectory = $script:AI_BACKEND_DIR
        RedirectStandardOutput = $stdoutLog
        RedirectStandardError = $stderrLog
        NoNewWindow = $true
        PassThru = $true
    }
    
    $process = Start-Process @processParams
    $aiBackendPid = $process.Id
    Set-Content -Path $script:AI_BACKEND_PID_FILE -Value $aiBackendPid
    
    Write-LogDebug "AI backend process started with PID: $aiBackendPid"
    
    # Wait a bit and check if process is still running
    Start-Sleep -Seconds 3
    
    $stillRunning = Get-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
    
    if ($stillRunning) {
        Write-LogSuccess "AI backend process started (PID: $aiBackendPid)"
        Write-LogInfo "AI backend logs:"
        Write-LogInfo "  stdout: Get-Content -Wait '$script:AI_BACKEND_LOG_FILE'"
        Write-LogInfo "  stderr: Get-Content -Wait '$script:AI_BACKEND_LOG_FILE.err'"
        
        if (-not (Wait-AiBackendReady)) {
            exit 1
        }
    }
    else {
        Write-LogError "AI backend failed to start. Check logs:"
        Write-LogError "  Get-Content '$script:AI_BACKEND_LOG_FILE'"
        Write-LogError "  Get-Content '$script:AI_BACKEND_LOG_FILE.err'"
        Remove-Item $script:AI_BACKEND_PID_FILE -Force -ErrorAction SilentlyContinue
        exit 1
    }
}

# Function to stop AI backend
function Stop-AiBackend {
    if (Test-Path $script:AI_BACKEND_PID_FILE) {
        $aiBackendPid = Get-Content $script:AI_BACKEND_PID_FILE -ErrorAction SilentlyContinue
        $process = Get-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
        if ($process) {
            Write-LogInfo "Stopping AI backend (PID: $aiBackendPid)..."
            
            # Try graceful shutdown first
            Stop-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
            
            # Wait for graceful shutdown
            $timeout = 10
            for ($i = 0; $i -lt $timeout; $i++) {
                $process = Get-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
                if (-not $process) {
                    break
                }
                Start-Sleep -Seconds 1
            }
            
            # Force kill if still running
            $process = Get-Process -Id $aiBackendPid -ErrorAction SilentlyContinue
            if ($process) {
                Write-LogWarning "Force killing AI backend..."
                Stop-Process -Id $aiBackendPid -Force -ErrorAction SilentlyContinue
            }
            
            Write-LogSuccess "AI backend stopped"
        }
        else {
            Write-LogWarning "AI backend PID file exists but process is not running (cleaning up stale PID file)"
        }
        
        Remove-Item $script:AI_BACKEND_PID_FILE -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-LogWarning "AI backend is not running"
    }
}

# Helper function to stop a process and all its children (only first-level children)
function Stop-ProcessAndChildren {
    param(
        [int]$ProcessId,
        [switch]$Force
    )

    $childProcessIds = @(Get-Process | Where-Object {
        $_.Parent -and $_.Parent.Id -eq $ProcessId
    } | Select-Object -ExpandProperty Id)

    # First stop child processes, to avoid zombies
    if ($childProcessIds.Count -gt 0) {
        Write-LogInfo "Stopping child processes (PIDs: $childProcessIds)..."
        foreach ($childId in $childProcessIds) {
            Stop-Process -Id $childId -Force:$Force -ErrorAction SilentlyContinue
        }
    }

    # Then stop the parent process
    Stop-Process -Id $ProcessId -Force:$Force -ErrorAction SilentlyContinue
}

# Function to stop backend
function Stop-Backend {
    if (Test-Path $script:BACKEND_PID_FILE) {
        $backendPid = Get-Content $script:BACKEND_PID_FILE -ErrorAction SilentlyContinue
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
        $frontendPid = Get-Content $script:FRONTEND_PID_FILE -ErrorAction SilentlyContinue
        $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
        if ($process) {
            Write-LogInfo "Stopping frontend (PID: $frontendPid)..."
            
            # Try graceful shutdown first, including children
            Stop-ProcessAndChildren -ProcessId $frontendPid
            
            # Wait for graceful shutdown
            $timeout = 10
            for ($i = 0; $i -lt $timeout; $i++) {
                $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
                if (-not $process) {
                    break
                }
                Start-Sleep -Seconds 1
            }
            
            # Force kill if still running, including children
            $process = Get-Process -Id $frontendPid -ErrorAction SilentlyContinue
            if ($process) {
                Write-LogWarning "Force killing frontend..."
                Stop-ProcessAndChildren -ProcessId $frontendPid -Force
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

    # Clean up any orphaned processes by looking for processes with our frontend directory path
    # This is safe and compatible across Windows and Unix systems in PowerShell
    $orphanedPids = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.Path -and $_.Path -like "*$script:FRONTEND_DIR*"
    } | Select-Object -ExpandProperty Id

    if ($orphanedPids) {
        foreach ($orphanPid in $orphanedPids) {
                $process = Get-Process -Id $orphanPid -ErrorAction SilentlyContinue
                if ($process) {
                    # Get process info to verify it's actually our frontend process
                    $processInfo = "$($process.ProcessName) $($process.Path)"

                    # Only kill if it's an npm/node/vite process AND contains our directory path
                    if ($process.ProcessName -match 'npm|node|vite' -and $process.Path -like "*$script:FRONTEND_DIR*") {
                        Write-LogWarning "Cleaning up orphaned process: PID $orphanPid - $processInfo"
                        Stop-Process -Id $orphanPid -ErrorAction SilentlyContinue
                        Start-Sleep -Seconds 1
                        # Force kill if still running
                        if (Get-Process -Id $orphanPid -ErrorAction SilentlyContinue) {
                            Stop-Process -Id $orphanPid -Force -ErrorAction SilentlyContinue
                        }
                    }
                }
        }
    }
}

# Helper function to display backend process status
function Get-BackendStatus {
    if (Test-Path $script:BACKEND_PID_FILE) {
        $processId = Get-Content $script:BACKEND_PID_FILE -ErrorAction SilentlyContinue
        if ($processId -and (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            Write-Host "Backend: " -NoNewline
            Write-Host "RUNNING" -ForegroundColor Green -NoNewline
            Write-Host " (PID: $processId)"
            return $true
        }
    }
    
    Write-Host "Backend: " -NoNewline
    Write-Host "STOPPED" -ForegroundColor Red
    return $false
}

# Helper function to display access information
function Show-AccessInformation {
    param(
        [string]$UiUrl,
        [bool]$ShowManualEdit = $true
    )

    $homeDir = Get-HomeDirectory
    
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
        Write-Host "  # Open the configuration file, by default: $homeDir\.opik.config"
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
    Write-Host "  • The configuration file is located at $homeDir\.opik.config by default"
    
    if ($ShowManualEdit) {
        Write-Host "  • You MUST remove '/api' from the URL for local development"
    }
    
    Write-Host "  • Default workspace is 'default'"
    Write-Host "  • No API key required for local instances"
    Write-Host ""
    Write-Host "📖 For complete configuration documentation, visit:" -ForegroundColor Blue
    Write-Host "   https://www.comet.com/docs/opik/tracing/sdk_configuration"
}

function New-DemoData {
    param([string]$Mode)
    
    Write-LogInfo "Creating demo data..."
    Push-Location $script:PROJECT_ROOT
    
    try {
        $opikScript = Join-Path $script:PROJECT_ROOT "opik.ps1"
        
        if (Test-Path $opikScript) {
            & $opikScript $Mode --demo-data
            if ($?) {
                Write-LogSuccess "Demo data created"
                return $true
            }
            else {
                Write-LogWarning "Demo data creation failed, but services are running"
                return $false
            }
        }
        return $false
    }
    finally {
        Pop-Location
    }
}

# Function to verify services
function Test-Services {
    Write-LogInfo "=== Opik Development Status ==="
    
    $dockerServicesRunning = Test-LocalBeFe
    if ($dockerServicesRunning) {
        Write-Host "Docker Services: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green
    }
    else {
        Write-Host "Docker Services: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # Backend process status
    $backendRunning = Get-BackendStatus
    
    # Frontend process status
    $frontendRunning = $false
    if (Test-Path $script:FRONTEND_PID_FILE) {
        $processId = Get-Content $script:FRONTEND_PID_FILE -ErrorAction SilentlyContinue
        if ($processId -and (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            Write-Host "Frontend Process: " -NoNewline
            Write-Host "RUNNING" -ForegroundColor Green -NoNewline
            Write-Host " (PID: $processId)"
            $frontendRunning = $true
        }
    }
    
    if (-not $frontendRunning) {
        Write-Host "Frontend Process: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # Show access information if all services are running
    if ($dockerServicesRunning -and $backendRunning -and $frontendRunning) {
        Show-AccessInformation -UiUrl "http://localhost:5174" -ShowManualEdit $true
    }
    
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  Backend Process:  Get-Content -Wait '$script:BACKEND_LOG_FILE'"
    Write-Host "  Frontend Process: Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
}

# Function to verify BE-only services
function Test-BeOnlyServices {
    Write-LogInfo "=== Opik BE-Only Development Status ==="
    
    $dockerServicesRunning = Test-LocalBe
    if ($dockerServicesRunning) {
        Write-Host "Docker Services: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green
    }
    else {
        Write-Host "Docker Services: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # Backend process status
    $backendRunning = Get-BackendStatus
    
    # Show access information if all services are running
    if ($dockerServicesRunning -and $backendRunning) {
        Show-AccessInformation -UiUrl "http://localhost:5173" -ShowManualEdit $false
    }
    
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  Backend Process:  Get-Content -Wait '$script:BACKEND_LOG_FILE'"
    Write-Host "  Frontend:         docker logs -f opik-frontend-1"
}

# Function to verify AI backend services
function Test-AiBackendServices {
    Write-LogInfo "=== Opik AI Backend Development Status ==="
    
    $dockerServicesRunning = Test-LocalAi
    if ($dockerServicesRunning) {
        Write-Host "Docker Services: " -NoNewline
        Write-Host "RUNNING" -ForegroundColor Green
    }
    else {
        Write-Host "Docker Services: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # AI backend process status
    $aiBackendRunning = $false
    if (Test-Path $script:AI_BACKEND_PID_FILE) {
        $processId = Get-Content $script:AI_BACKEND_PID_FILE -ErrorAction SilentlyContinue
        if ($processId -and (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            Write-Host "AI Backend Process: " -NoNewline
            Write-Host "RUNNING" -ForegroundColor Green -NoNewline
            Write-Host " (PID: $processId)"
            $aiBackendRunning = $true
        }
    }
    
    if (-not $aiBackendRunning) {
        Write-Host "AI Backend Process: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # Frontend process status
    $frontendRunning = $false
    if (Test-Path $script:FRONTEND_PID_FILE) {
        $processId = Get-Content $script:FRONTEND_PID_FILE -ErrorAction SilentlyContinue
        if ($processId -and (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            Write-Host "Frontend Process: " -NoNewline
            Write-Host "RUNNING" -ForegroundColor Green -NoNewline
            Write-Host " (PID: $processId)"
            $frontendRunning = $true
        }
    }
    
    if (-not $frontendRunning) {
        Write-Host "Frontend Process: " -NoNewline
        Write-Host "STOPPED" -ForegroundColor Red
    }
    
    # Show access information if all services are running
    if ($dockerServicesRunning -and $aiBackendRunning -and $frontendRunning) {
        Show-AccessInformation -UiUrl "http://localhost:5174" -ShowManualEdit $true
    }
    
    Write-Host ""
    Write-Host "Logs:"
    Write-Host "  AI Backend Process: Get-Content -Wait '$script:AI_BACKEND_LOG_FILE'"
    Write-Host "  Frontend Process:   Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
    Write-Host "  Backend:            docker logs -f opik-backend-1"
}

# Function to start services (without building)
function Start-Services {
    Write-LogInfo "=== Starting Opik Development Environment ==="
    Write-LogWarning "=== Not rebuilding: the latest local changes may not be reflected ==="
    Write-LogInfo "Step 1/5: Starting Docker services..."
    Start-LocalBeFe
    Write-LogInfo "Step 2/5: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 3/5: Starting backend process..."
    Start-Backend
    Write-LogInfo "Step 4/5: Starting frontend process..."
    Start-Frontend
    Write-LogInfo "Step 5/5: Creating demo data..."
    New-DemoData -Mode "--local-be-fe"
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
    Write-LogInfo "Step 3/3: Stopping Docker services..."
    Stop-LocalBeFe
    Write-LogSuccess "=== Stop Complete ==="
}

# Function to run migrations
function Invoke-Migrations {
    Write-LogInfo "=== Running Database Migrations ==="
    Write-LogInfo "Step 1/3: Starting Docker services..."
    Start-LocalBeFe
    Write-LogInfo "Step 2/3: Building backend..."
    Build-Backend
    Write-LogInfo "Step 3/3: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogSuccess "=== Migrations Complete ==="
}

# Function to restart services (stop, build, start)
function Restart-Services {
    Write-LogInfo "=== Restarting Opik Development Environment ==="
    Write-LogInfo "Step 1/10: Stopping frontend process..."
    Stop-Frontend
    Write-LogInfo "Step 2/10: Stopping backend process..."
    Stop-Backend
    Write-LogInfo "Step 3/10: Stopping Docker services..."
    Stop-LocalBeFe
    Write-LogInfo "Step 4/10: Starting Docker services..."
    Start-LocalBeFe
    Write-LogInfo "Step 5/10: Building backend..."
    Build-Backend
    Write-LogInfo "Step 6/10: Building frontend..."
    Build-Frontend
    Write-LogInfo "Step 7/10: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 8/10: Starting backend process..."
    Start-Backend
    Write-LogInfo "Step 9/10: Starting frontend process..."
    Start-Frontend
    Write-LogInfo "Step 10/10: Creating demo data..."
    New-DemoData -Mode "--local-be-fe"
    Write-LogSuccess "=== Restart Complete ==="
    Test-Services
}

# Function for quick restart (only rebuild backend, keep infrastructure running)
function Invoke-QuickRestart {
    Write-LogInfo "=== Quick Restart & Build (Backend, Frontend only) ==="
    
    # Check if infrastructure is running, start it if not
    Write-LogInfo "Step 1/7: Checking Docker infrastructure..."
    if (Test-LocalBeFe) {
        Write-LogSuccess "Docker infrastructure is already running"
    }
    else {
        Write-LogWarning "Docker infrastructure is not running, starting it..."
        Start-LocalBeFe
        Write-LogInfo "Running DB migrations..."
        Invoke-DbMigrations
    }
    
    Write-LogInfo "Step 2/7: Stopping frontend..."
    Stop-Frontend
    Write-LogInfo "Step 3/7: Stopping backend..."
    Stop-Backend
    Write-LogInfo "Step 4/7: Building backend..."
    Build-Backend
    Write-LogInfo "Step 5/7: Starting backend..."
    Start-Backend
    
    # Check if package.json has changed since last npm install
    Write-LogInfo "Step 6/7: Checking frontend dependencies..."
    $packageJson = Join-Path $script:FRONTEND_DIR "package.json"
    $packageLock = Join-Path $script:FRONTEND_DIR "package-lock.json"
    $nodeModules = Join-Path $script:FRONTEND_DIR "node_modules"
    
    $needsInstall = $false
    
    if (-not (Test-Path $nodeModules)) {
        Write-LogInfo "node_modules not found, will install dependencies"
        $needsInstall = $true
    }
    elseif (-not (Test-Path $packageLock)) {
        Write-LogInfo "package-lock.json not found, will install dependencies"
        $needsInstall = $true
    }
    elseif ((Get-Item $packageJson).LastWriteTime -gt (Get-Item $packageLock).LastWriteTime) {
        Write-LogInfo "package.json is newer than package-lock.json, will install dependencies"
        $needsInstall = $true
    }
    else {
        Write-LogInfo "Frontend dependencies are up to date, skipping npm install"
    }
    
    if ($needsInstall) {
        Build-Frontend
    }
    
    Write-LogInfo "Step 7/7: Starting frontend..."
    Start-Frontend
    Write-LogSuccess "=== Quick Restart Complete ==="
    Test-Services
}

# Function to start BE-only services (without building)
function Start-BeOnlyServices {
    Write-LogInfo "=== Starting Opik BE-Only Development Environment ==="
    Write-LogWarning "=== Not rebuilding: the latest local changes may not be reflected ==="
    Write-LogInfo "Step 1/4: Starting Docker services..."
    Start-LocalBe
    Write-LogInfo "Step 2/4: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 3/4: Starting backend process..."
    Start-Backend
    Write-LogInfo "Step 4/4: Creating demo data..."
    New-DemoData -Mode "--local-be"
    Write-LogSuccess "=== BE-Only Start Complete ==="
    Test-BeOnlyServices
}

# Function to stop BE-only services
function Stop-BeOnlyServices {
    Write-LogInfo "=== Stopping Opik BE-Only Development Environment ==="
    Write-LogInfo "Step 1/2: Stopping backend process..."
    Stop-Backend
    Write-LogInfo "Step 2/2: Stopping Docker services..."
    Stop-LocalBe
    Write-LogSuccess "=== BE-Only Stop Complete ==="
}

# Function to restart BE-only services (stop, build, start)
function Restart-BeOnlyServices {
    Write-LogInfo "=== Restarting Opik BE-Only Development Environment ==="
    Write-LogInfo "Step 1/7: Stopping backend process..."
    Stop-Backend
    Write-LogInfo "Step 2/7: Stopping Docker services..."
    Stop-LocalBe
    Write-LogInfo "Step 3/7: Starting Docker services..."
    Start-LocalBe
    Write-LogInfo "Step 4/7: Building backend..."
    Build-Backend
    Write-LogInfo "Step 5/7: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 6/7: Starting backend process..."
    Start-Backend
    Write-LogInfo "Step 7/7: Creating demo data..."
    New-DemoData -Mode "--local-be"
    Write-LogSuccess "=== BE-Only Restart Complete ==="
    Test-BeOnlyServices
}

# Function to start AI backend services (without building)
function Start-AiBackendServices {
    Write-LogInfo "=== Starting Opik AI Backend Development Environment ==="
    Write-LogWarning "=== Not rebuilding: the latest local changes may not be reflected ==="
    
    # Enable OpikAI feature toggle for the backend
    $env:TOGGLE_OPIK_AI_ENABLED = "true"
    
    Write-LogInfo "Step 1/5: Starting Docker services..."
    Start-LocalAi
    Write-LogInfo "Step 2/5: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 3/5: Starting AI backend process..."
    Start-AiBackend
    Write-LogInfo "Step 4/5: Starting frontend process..."
    Start-Frontend
    Write-LogInfo "Step 5/5: Creating demo data..."
    New-DemoData -Mode "--local-ai"
    Write-LogSuccess "=== AI Backend Start Complete ==="
    Test-AiBackendServices
}

# Function to stop AI backend services
function Stop-AiBackendServices {
    Write-LogInfo "=== Stopping Opik AI Backend Development Environment ==="
    Write-LogInfo "Step 1/3: Stopping frontend..."
    Stop-Frontend
    Write-LogInfo "Step 2/3: Stopping AI backend..."
    Stop-AiBackend
    Write-LogInfo "Step 3/3: Stopping Docker services..."
    Stop-LocalAi
    Write-LogSuccess "=== AI Backend Stop Complete ==="
}

# Function to restart AI backend services (stop, build, start)
function Restart-AiBackendServices {
    Write-LogInfo "=== Restarting Opik AI Backend Development Environment ==="
    Write-LogInfo "Step 1/9: Stopping frontend process..."
    Stop-Frontend
    Write-LogInfo "Step 2/9: Stopping AI backend process..."
    Stop-AiBackend
    Write-LogInfo "Step 3/9: Stopping Docker services..."
    Stop-LocalAi
    
    # Enable OpikAI feature toggle for the backend
    $env:TOGGLE_OPIK_AI_ENABLED = "true"
    
    Write-LogInfo "Step 4/9: Starting Docker services..."
    Start-LocalAi
    Write-LogInfo "Step 5/9: Running DB migrations..."
    Invoke-DbMigrations
    Write-LogInfo "Step 6/9: Building AI backend..."
    Build-AiBackend
    Write-LogInfo "Step 7/9: Building frontend..."
    Build-Frontend
    Write-LogInfo "Step 8/9: Starting AI backend process..."
    Start-AiBackend
    Write-LogInfo "Step 9/9: Starting frontend process..."
    Start-Frontend
    Write-LogSuccess "=== AI Backend Restart Complete ==="
    Test-AiBackendServices
}

# Function to show logs
function Show-Logs {
    Write-LogInfo "=== Recent Logs ==="
    
    if (Test-Path $script:BACKEND_LOG_FILE) {
        Write-Host "`nBackend stdout logs (last 20 lines):" -ForegroundColor Blue
        Get-Content $script:BACKEND_LOG_FILE -Tail 20
    }
    
    if (Test-Path "$script:BACKEND_LOG_FILE.err") {
        Write-Host "`nBackend stderr logs (last 20 lines):" -ForegroundColor Blue
        Get-Content "$script:BACKEND_LOG_FILE.err" -Tail 20
    }
    
    if (Test-Path $script:FRONTEND_LOG_FILE) {
        Write-Host "`nFrontend stdout logs (last 20 lines):" -ForegroundColor Blue
        Get-Content $script:FRONTEND_LOG_FILE -Tail 20
    }
    
    if (Test-Path "$script:FRONTEND_LOG_FILE.err") {
        Write-Host "`nFrontend stderr logs (last 20 lines):" -ForegroundColor Blue
        Get-Content "$script:FRONTEND_LOG_FILE.err" -Tail 20
    }
    
    Write-Host "`nTo follow logs in real-time:" -ForegroundColor Blue
    Write-Host "  Backend stdout:  Get-Content -Wait '$script:BACKEND_LOG_FILE'"
    Write-Host "  Backend stderr:  Get-Content -Wait '$script:BACKEND_LOG_FILE.err'"
    Write-Host "  Frontend stdout: Get-Content -Wait '$script:FRONTEND_LOG_FILE'"
    Write-Host "  Frontend stderr: Get-Content -Wait '$script:FRONTEND_LOG_FILE.err'"
}

# Function to show usage
function Show-Usage {
    Write-Host "Usage: $PSCommandPath [OPTIONS]"
    Write-Host ""
    Write-Host "Standard Mode (BE and FE services as processes):"
    Write-Host "  --start         - Start Docker infrastructure, and BE and FE processes (without building)"
    Write-Host "  --stop          - Stop Docker infrastructure, and BE and FE processes"
    Write-Host "  --restart       - Stop, build, and start Docker infrastructure, and BE and FE processes (DEFAULT IF NO OPTIONS PROVIDED)"
    Write-Host "  --quick-restart - Quick restart: stop BE/FE, rebuild BE only, start BE/FE (keeps infrastructure running)"
    Write-Host "  --verify        - Verify status of Docker infrastructure, and BE and FE processes"
    Write-Host ""
    Write-Host "BE-Only Mode (BE as process, FE in Docker):"
    Write-Host "  --be-only-start    - Start Docker infrastructure and FE, and backend process (without building)"
    Write-Host "  --be-only-stop     - Stop Docker infrastructure and FE, and backend process"
    Write-Host "  --be-only-restart  - Stop, build, and start Docker infrastructure and FE, and backend process"
    Write-Host "  --be-only-verify   - Verify status of Docker infrastructure and FE, and backend process"
    Write-Host ""
    Write-Host "AI Backend Mode (AI backend as process, backend + Python backend + FE in Docker):"
    Write-Host "  --ai-backend-start    - Start Docker infrastructure, backend, Python backend and FE, and AI backend process (without building)"
    Write-Host "  --ai-backend-stop     - Stop Docker infrastructure, backend, Python backend and FE, and AI backend process"
    Write-Host "  --ai-backend-restart  - Stop, build, and start Docker infrastructure, backend, Python backend and FE, and AI backend process"
    Write-Host "  --ai-backend-verify   - Verify status of Docker infrastructure, backend, Python backend and FE, and AI backend process"
    Write-Host ""
    Write-Host "Other options:"
    Write-Host "  --build-be     - Build backend"
    Write-Host "  --build-fe     - Build frontend"
    Write-Host "  --build-ai     - Build AI backend"
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
    "--build-ai" {
        Build-AiBackend
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
    "--quick-restart" {
        Invoke-QuickRestart
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
    "--ai-backend-start" {
        Start-AiBackendServices
    }
    "--ai-backend-stop" {
        Stop-AiBackendServices
    }
    "--ai-backend-restart" {
        Restart-AiBackendServices
    }
    "--ai-backend-verify" {
        Test-AiBackendServices
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

