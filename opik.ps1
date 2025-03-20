# opik.ps1

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

$REQUIRED_CONTAINERS = @(
    "opik-clickhouse-1",
    "opik-mysql-1",
    "opik-python-backend-1",
    "opik-redis-1",
    "opik-frontend-1",
    "opik-backend-1"
)

function Print-Usage {
    Write-Host "Usage: opik.ps1 [OPTION]"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  --verify    Check if all containers are healthy"
    Write-Host "  --info      Display welcome system status, only if all containers are running"
    Write-Host "  --stop      Stop all containers and clean up"
    Write-Host "  --debug     Enable debug mode (verbose output)"
    Write-Host "  --help      Show this help message"
    Write-Host ""
    Write-Host "If no option is passed, the script will start missing containers and then show the system status."
}

function Check-DockerStatus {
    try {
        docker info | Out-Null
    } catch {
        Write-Host "❌ Docker is not running or not accessible. Please start Docker first."
        exit 1
    }
}

function Check-ContainersStatus {
    param (
        [bool]$ShowOutput = $false
    )
    Check-DockerStatus
    $allOk = $true

    foreach ($container in $REQUIRED_CONTAINERS) {
        $status = docker inspect -f '{{.State.Status}}' $container 2>$null
        $health = docker inspect -f '{{.State.Health.Status}}' $container 2>$null

        if ($status -ne "running") {
            Write-Host "❌ $container is not running (status=$status)"
            $allOk = $false
        } elseif ($health -and $health -ne "healthy") {
            Write-Host "❌ $container is running but not healthy (health=$health)"
            $allOk = $false
        } elseif ($ShowOutput) {
            Write-Host "✅ $container is running and healthy"
        }
    }

    if (-not $allOk) { return $false } else { return $true }
}

function Start-MissingContainers {
    Check-DockerStatus

    if ($DEBUG_MODE) { Write-Host "🔍 Checking required containers..." }
    $allRunning = $true

    foreach ($container in $REQUIRED_CONTAINERS) {
        $status = docker inspect -f '{{.State.Status}}' $container 2>$null
        if ($status -ne "running") {
            if ($DEBUG_MODE) { Write-Host "🔴 $container is not running (status: $($status -or 'not found'))" }
            $allRunning = $false
        } elseif ($DEBUG_MODE) {
            Write-Host "✅ $container is already running"
        }
    }

    if ($allRunning) {
        Write-Host "🚀 All required containers are already running!"
        return
    }

    Write-Host "🔄 Starting missing containers..."
    docker compose -f "$scriptDir/deployment/docker-compose/docker-compose.yaml" up -d

    Write-Host "⏳ Waiting for all containers to be running and healthy..."
    $maxRetries = 60
    $interval = 1

    foreach ($container in $REQUIRED_CONTAINERS) {
        $retries = 0
        if ($DEBUG_MODE) { Write-Host "⏳ Waiting for $container..." }

        while ($true) {
            $status = docker inspect -f '{{.State.Status}}' $container 2>$null
            $health = docker inspect -f '{{.State.Health.Status}}' $container 2>$null

            if ($status -ne "running") {
                Write-Host "❌ $container failed to start (status: $status)"
                break
            }

            if ($health -eq "healthy") {
                if ($DEBUG_MODE) { Write-Host "✅ $container is now running and healthy!" }
                break
            } elseif ($health -eq "starting") {
                if ($DEBUG_MODE) { Write-Host "⏳ $container is starting... retrying (${retries}s)" }
                Start-Sleep -Seconds $interval
                $retries++
                if ($retries -ge $maxRetries) {
                    Write-Host "⚠️  $container is still not healthy after ${maxRetries}s"
                    break
                }
            } else {
                Write-Host "❌ $container health state is '$health'"
                break
            }
        }
    }

    Write-Host "✅ All required containers are now running!"
}

function Stop-Containers {
    Check-DockerStatus
    Write-Host "🛑 Stopping all required containers..."
    docker compose -f "$scriptDir/deployment/docker-compose/docker-compose.yaml" stop
    Write-Host "✅ All containers stopped and cleaned up!"
}

function Print-Banner {
    Check-DockerStatus
    $frontendPort = docker inspect -f '{{ (index (index .NetworkSettings.Ports "5173/tcp") 0).HostPort }}' opik-frontend-1 2>$null
    $uiUrl = "http://localhost:$($frontendPort -or 5173)"

    Write-Host ""
    Write-Host "╔═════════════════════════════════════════════════════════════════╗"
    Write-Host "║                                                                 ║"
    Write-Host "║                  🚀 OPIK PLATFORM 🚀                            ║"
    Write-Host "║                                                                 ║"
    Write-Host "╠═════════════════════════════════════════════════════════════════╣"
    Write-Host "║                                                                 ║"
    Write-Host "║  ✅ All services started successfully!                          ║"
    Write-Host "║                                                                 ║"
    Write-Host "║  📊 Access the UI:                                              ║"
    Write-Host "║     $uiUrl                                                      ║"
    Write-Host "║                                                                 ║"
    Write-Host "║  🛠️  Configure the Python SDK:                                   ║"
    Write-Host "║     `opik configure`                                            ║"
    Write-Host "║                                                                 ║"
    Write-Host "║  📚 Documentation: https://www.comet.com/docs/opik/             ║"
    Write-Host "║                                                                 ║"
    Write-Host "║  💬 Need help? Join our community: https://chat.comet.com       ║"
    Write-Host "║                                                                 ║"
    Write-Host "╚═════════════════════════════════════════════════════════════════╝"
}

# Default: no debug
$DEBUG_MODE = $false

# Main logic
param (
    [string]$option = ""
)

switch ($option) {
    "--verify" {
        Write-Host "🔍 Verifying container health..."
        $result = Check-ContainersStatus $true
        if ($result) { exit 0 } else { exit 1 }
    }
    "--info" {
        Write-Host "ℹ️  Checking if all containers are up before displaying system status..."
        if (Check-ContainersStatus $true) {
            Print-Banner
            exit 0
        } else {
            Write-Host "⚠️  Some containers are not running/healthy. Please run 'opik.ps1' to start them."
            exit 1
        }
    }
    "--stop" {
        Stop-Containers
        exit 0
    }
    "--help" {
        Print-Usage
        exit 0
    }
    "--debug" {
        $DEBUG_MODE = $true
        Write-Host "🐞 Debug mode enabled."
        Write-Host "🔍 Checking container status and starting missing ones..."
        Start-MissingContainers
        Start-Sleep -Seconds 2
        Write-Host "🔄 Re-checking container status..."
        if (Check-ContainersStatus $true) {
            Print-Banner
        } else {
            Write-Host "⚠️  Some containers are still not healthy. Please check manually using 'opik.ps1 --verify'"
            exit 1
        }
    }
    "" {
        Write-Host "🔍 Checking container status and starting missing ones..."
        Start-MissingContainers
        Start-Sleep -Seconds 2
        Write-Host "🔄 Re-checking container status..."
        if (Check-ContainersStatus $true) {
            Print-Banner
        } else {
            Write-Host "⚠️  Some containers are still not healthy. Please check manually using 'opik.ps1 --verify'"
            exit 1
        }
    }
    Default {
        Write-Host "❌ Unknown option: $option"
        Print-Usage
        exit 1
    }
}
