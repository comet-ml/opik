# Podman Runtime Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow Opik to run with Podman as an alternative to Docker by abstracting the container runtime across `opik.sh`, `opik.ps1`, and `scripts/dev-runner.sh`.

**Architecture:** A new `resolve_container_runtime()` function in `scripts/worktree-utils.sh` (sourced by both bash scripts) auto-detects Docker or Podman and sets three exports: `CONTAINER_RUNTIME`, `COMPOSE_CMD`, and `OPIK_HOST_GATEWAY`. All docker-hardcoded call sites are replaced with these variables. `opik.sh` parses a new `--runtime` flag before calling the detector. `opik.ps1` mirrors the same logic in PowerShell.

**Tech Stack:** Bash 4+, PowerShell 5.1+, Docker Compose v2, podman-compose 1.0+ or Podman 4.7+

---

## File Map

| File | Action | What changes |
|------|--------|-------------|
| `scripts/worktree-utils.sh` | Modify | Add `resolve_container_runtime()` before `init_worktree_ports()` |
| `opik.sh` | Modify | Early `--runtime` parsing + call `resolve_container_runtime`; replace 10 `docker` call sites; update telemetry; update `print_usage` |
| `opik.ps1` | Modify | Add `-Runtime` param + detection block; replace 10 `docker` call sites; update `Get-SystemInfo` and `Send-InstallReport` |
| `scripts/dev-runner.sh` | Modify | Call `resolve_container_runtime` after `init_worktree_ports`; fix `docker logs` hint |
| `deployment/docker-compose/docker-compose.override.yaml` | Modify | Use `${OPIK_HOST_GATEWAY}` instead of hardcoded `host.docker.internal` |
| `deployment/docker-compose/docker-compose.yaml` | **Conditional** | Fix non-standard volume syntax only if Task 1 verification fails |
| `apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx` | Modify | Podman prerequisites, version requirements, `--runtime` flag docs, troubleshooting note |

---

## Task 1: Pre-implementation — verify Podman Compose compatibility

**Files:**
- Read: `deployment/docker-compose/docker-compose.yaml`

This task determines whether Task 10 is needed. Run it on a system with Podman installed before writing any code.

- [ ] **Step 1: Run podman compose config**

```bash
cd /path/to/opik
podman compose -f deployment/docker-compose/docker-compose.yaml config
```

Expected if compatible: YAML output, exit 0, no warnings about volume lines.
Expected if incompatible: Error mentioning lines 19, 57, or 58 (the `type=volume,source=~` volume entries).

- [ ] **Step 2: Record the result**

If exit 0 with no warnings: **Task 10 is SKIPPED** — the compose file needs no changes.

If error about volume syntax: **Task 10 is REQUIRED** — note the exact error before proceeding.

- [ ] **Step 3: Commit nothing — this is a verification step only**

---

## Task 2: Add `resolve_container_runtime()` to `scripts/worktree-utils.sh`

**Files:**
- Modify: `scripts/worktree-utils.sh` — insert before line 93 (`init_worktree_ports()`)

- [ ] **Step 1: Insert the function**

Open `scripts/worktree-utils.sh`. Find the comment `# Initialize worktree variables` (line 93). Insert the following block immediately before it:

```bash
# Detect and validate container runtime (docker or podman).
# Sets CONTAINER_RUNTIME, COMPOSE_CMD, OPIK_HOST_GATEWAY and exports all three.
# Honors CONTAINER_RUNTIME if already set by caller (e.g. --runtime flag).
resolve_container_runtime() {
    if [[ -n "${CONTAINER_RUNTIME:-}" ]]; then
        if [[ "$CONTAINER_RUNTIME" != "docker" && "$CONTAINER_RUNTIME" != "podman" ]]; then
            echo "❌ Invalid runtime '$CONTAINER_RUNTIME'. Must be 'docker' or 'podman'."
            exit 1
        fi
    else
        if docker info >/dev/null 2>&1; then
            CONTAINER_RUNTIME="docker"
        elif podman info >/dev/null 2>&1; then
            CONTAINER_RUNTIME="podman"
        else
            echo "❌ Neither Docker nor Podman is available. Please install one first."
            exit 1
        fi
    fi

    if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
        if podman compose version >/dev/null 2>&1; then
            COMPOSE_CMD="podman compose"
        elif command -v podman-compose >/dev/null 2>&1; then
            COMPOSE_CMD="podman-compose"
        else
            echo "❌ Podman found but no compose tool available."
            echo "   Option 1: Upgrade to Podman 4.7+ (includes 'podman compose')"
            echo "   Option 2: pip install podman-compose>=1.0"
            exit 1
        fi
        export OPIK_HOST_GATEWAY="host.containers.internal"
    else
        COMPOSE_CMD="docker compose"
        export OPIK_HOST_GATEWAY="host.docker.internal"
    fi

    export CONTAINER_RUNTIME COMPOSE_CMD
}

```

- [ ] **Step 2: Syntax check**

```bash
bash -n scripts/worktree-utils.sh
```

Expected: no output, exit 0.

- [ ] **Step 3: Commit**

```bash
git add scripts/worktree-utils.sh
git commit -m "feat(infra): add resolve_container_runtime to worktree-utils"
```

---

## Task 3: `opik.sh` — early `--runtime` parsing and runtime resolution

**Files:**
- Modify: `opik.sh` lines 7–8 (after `init_worktree_ports`)

- [ ] **Step 1: Insert `--runtime` parsing block**

In `opik.sh`, find line 7 (`init_worktree_ports`). Insert the following block immediately after it (before the container-name array declarations on line 10):

```bash

# Parse --runtime before all other flags so CONTAINER_RUNTIME is available early.
# This must run before flag parsing below, which uses $OPIK_HOST_GATEWAY.
CONTAINER_RUNTIME="${OPIK_CONTAINER_RUNTIME:-}"
_OPIK_NEW_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --runtime)
      if [[ -z "${2:-}" || "${2:-}" == --* ]]; then
        echo "❌ --runtime requires a value: docker or podman"
        exit 1
      fi
      CONTAINER_RUNTIME="$2"
      shift 2
      ;;
    *) _OPIK_NEW_ARGS+=("$1"); shift ;;
  esac
done
set -- "${_OPIK_NEW_ARGS[@]}"
unset _OPIK_NEW_ARGS

resolve_container_runtime
```

- [ ] **Step 2: Syntax check**

```bash
bash -n opik.sh
```

Expected: no output, exit 0.

- [ ] **Step 3: Smoke test — runtime detection works with Docker**

```bash
CONTAINER_RUNTIME="" ./opik.sh --help
```

Expected: help text printed, no error about Docker/Podman. Also confirm:

```bash
./opik.sh --runtime foo --help
```

Expected: `❌ Invalid runtime 'foo'. Must be 'docker' or 'podman'.` then exit 1.

```bash
./opik.sh --runtime --help
```

Expected: `❌ --runtime requires a value: docker or podman` then exit 1.

- [ ] **Step 4: Commit**

```bash
git add opik.sh
git commit -m "feat(infra): add --runtime flag parsing and runtime detection to opik.sh"
```

---

## Task 4: `opik.sh` — rename `check_docker_status` → `check_runtime_status`

**Files:**
- Modify: `opik.sh` lines 255–261 (function definition) and lines 267, 323, 402, 411, 426, 454 (call sites)

- [ ] **Step 1: Replace the function definition**

Find and replace lines 255–261:

```bash
# BEFORE
check_docker_status() {
  # Ensure Docker is running
  if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker is not running or not accessible. Please start Docker first."
    exit 1
  fi
}

# AFTER
check_runtime_status() {
  if ! $CONTAINER_RUNTIME info >/dev/null 2>&1; then
    echo "❌ ${CONTAINER_RUNTIME^} is not running or not accessible. Please start ${CONTAINER_RUNTIME^} first."
    exit 1
  fi
}
```

- [ ] **Step 2: Replace all six call sites**

Use your editor or the following command to replace all occurrences:

```bash
sed -i 's/check_docker_status/check_runtime_status/g' opik.sh
```

Verify the replacement touched exactly 6 call sites (line 267, 323, 402, 411, 426, 454 in the original):

```bash
grep -n "check_runtime_status\|check_docker_status" opik.sh
```

Expected: 7 lines — 1 definition + 6 calls, all showing `check_runtime_status`, none showing `check_docker_status`.

- [ ] **Step 3: Syntax check**

```bash
bash -n opik.sh
```

- [ ] **Step 4: Commit**

```bash
git add opik.sh
git commit -m "feat(infra): replace check_docker_status with check_runtime_status in opik.sh"
```

---

## Task 5: `opik.sh` — `get_docker_compose_cmd` and `setup_buildx_bake`

**Files:**
- Modify: `opik.sh` line 178 and lines 108–122

- [ ] **Step 1: Update `get_docker_compose_cmd` to use `$COMPOSE_CMD`**

Find line 178:
```bash
# BEFORE
  local cmd="docker compose -p ${COMPOSE_PROJECT_NAME} -f $script_dir/deployment/docker-compose/docker-compose.yaml"

# AFTER
  local cmd="$COMPOSE_CMD -p ${COMPOSE_PROJECT_NAME} -f $script_dir/deployment/docker-compose/docker-compose.yaml"
```

- [ ] **Step 2: Update `setup_buildx_bake` with Podman guard**

Replace lines 108–122 with:

```bash
setup_buildx_bake() {
  if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
    export COMPOSE_BAKE=false
    return
  fi
  if [[ "${BUILD_MODE}" = "true" ]]; then
    if [[ "${COMPOSE_BAKE:-}" = "false" ]]; then
      echo "ℹ️ COMPOSE_BAKE is explicitly disabled. Skipping Bake-enabled builds"
      return
    fi

    if docker buildx bake --help >/dev/null 2>&1; then
      echo "ℹ️ Bake is available on Docker Buildx. Exporting COMPOSE_BAKE=true"
      export COMPOSE_BAKE=true
    else
      echo "ℹ️ Bake is not available on Docker Buildx. Not using it for builds"
    fi
  fi
}
```

- [ ] **Step 3: Syntax check**

```bash
bash -n opik.sh
```

- [ ] **Step 4: Commit**

```bash
git add opik.sh
git commit -m "feat(infra): use COMPOSE_CMD variable and guard buildx bake for Podman in opik.sh"
```

---

## Task 6: `opik.sh` — replace all `docker inspect` / `docker logs` call sites

**Files:**
- Modify: `opik.sh` — 7 substitution sites across 3 functions

- [ ] **Step 1: Update `check_containers_status` (lines 272–273)**

```bash
# BEFORE
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
    health=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)

# AFTER
    status=$($CONTAINER_RUNTIME inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
    health=$($CONTAINER_RUNTIME inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)
```

- [ ] **Step 2: Update `wait_for_container_completion` (lines 301, 305, 318)**

```bash
# BEFORE (line 301)
    status=$(docker inspect -f '{{.State.Status}}' "$container_name" 2>/dev/null || echo "not_found")
# AFTER
    status=$($CONTAINER_RUNTIME inspect -f '{{.State.Status}}' "$container_name" 2>/dev/null || echo "not_found")

# BEFORE (line 305)
      exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$container_name" 2>/dev/null || echo "1")
# AFTER
      exit_code=$($CONTAINER_RUNTIME inspect -f '{{.State.ExitCode}}' "$container_name" 2>/dev/null || echo "1")

# BEFORE (line 318)
  docker logs "$container_name" 2>/dev/null || true
# AFTER
  $CONTAINER_RUNTIME logs "$container_name" 2>/dev/null || true
```

- [ ] **Step 3: Update `start_missing_containers` (lines 339, 367–368)**

```bash
# BEFORE (line 339)
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
# AFTER
    status=$($CONTAINER_RUNTIME inspect -f '{{.State.Status}}' "$container" 2>/dev/null)

# BEFORE (lines 367-368)
      status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
      health=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)
# AFTER
      status=$($CONTAINER_RUNTIME inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
      health=$($CONTAINER_RUNTIME inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)
```

- [ ] **Step 4: Verify no `docker inspect` or `docker logs` remain as raw calls**

```bash
grep -n "docker inspect\|docker logs" opik.sh
```

Expected: zero output.

- [ ] **Step 5: Syntax check**

```bash
bash -n opik.sh
```

- [ ] **Step 6: Commit**

```bash
git add opik.sh
git commit -m "feat(infra): replace docker inspect/logs with \$CONTAINER_RUNTIME in opik.sh"
```

---

## Task 7: `opik.sh` — runtime-aware telemetry in `get_system_info` and `send_install_report`

**Files:**
- Modify: `opik.sh` lines 124–174 (`get_system_info`) and lines 573–590 (`send_install_report`)

- [ ] **Step 1: Replace `get_system_info` body (lines 143–173)**

Find the comment `# Docker version - safe with fallback` (line 143) and replace everything from there to `printf "%s\t%s\t%s"` (line 173) with:

```bash
  # Runtime version - safe with fallback
  local runtime_version="unknown"
  if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
    runtime_version=$(podman --version 2>/dev/null | sed -n 's/^podman version \(.*\)/\1/p' || echo "unknown")
    [[ -z "$runtime_version" ]] && runtime_version="unknown"
  else
    if command -v docker >/dev/null 2>&1; then
      local docker_output=$(docker --version 2>/dev/null || echo "")
      if [[ -n "$docker_output" ]]; then
        runtime_version=$(echo "$docker_output" | sed -n 's/^Docker version \([^,]*\).*/\1/p' || echo "unknown")
        [[ -z "$runtime_version" ]] && runtime_version="unknown"
      fi
    fi
  fi

  # Compose version - safe with fallback
  local compose_version="unknown"
  if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
    local compose_output=$($COMPOSE_CMD version 2>/dev/null || echo "")
    if [[ -n "$compose_output" ]]; then
      compose_version=$(echo "$compose_output" | head -1 | sed 's/.*version[[:space:]]*//' || echo "unknown")
      [[ -z "$compose_version" ]] && compose_version="unknown"
    fi
  else
    if command -v docker >/dev/null 2>&1; then
      local compose_output=$(docker compose version 2>/dev/null || echo "")
      if [[ -n "$compose_output" ]]; then
        compose_version=$(echo "$compose_output" | sed -n 's/^Docker Compose version \(.*\)$/\1/p' || echo "unknown")
        [[ -z "$compose_version" ]] && compose_version="unknown"
      fi
    fi
    if [[ "$compose_version" == "unknown" ]] && command -v docker-compose >/dev/null 2>&1; then
      compose_version=$(docker-compose version --short 2>/dev/null || echo "unknown")
    fi
  fi

  # Return tab-delimited: os, runtime_version, compose_version, runtime_name
  printf "%s\t%s\t%s\t%s" "$os_info" "$runtime_version" "$compose_version" "$CONTAINER_RUNTIME"
```

- [ ] **Step 2: Update `send_install_report` (lines 573–590)**

Find line 573:
```bash
# BEFORE
    system_info=$(get_system_info 2>/dev/null || printf "unknown\tunknown\tunknown")
    IFS=$'\t' read -r os_info docker_ver docker_compose_ver <<< "$system_info"
    
    debugLog "[DEBUG] System info: OS=$os_info, Docker=$docker_ver, Docker Compose=$docker_compose_ver"
    
    json_payload=$(cat <<EOF
{
  "anonymous_id": "$uuid",
  "event_type": "$event_type",
  "event_properties": {
    "start_time": "$start_time",
    "event_ver": "1",
    "script_type": "sh",
    "os": "$os_info",
    "docker_version": "$docker_ver",
    "docker_compose_version": "$docker_compose_ver"
  }
}
EOF
)

# AFTER
    system_info=$(get_system_info 2>/dev/null || printf "unknown\tunknown\tunknown\tunknown")
    IFS=$'\t' read -r os_info runtime_ver compose_ver runtime_name <<< "$system_info"
    
    debugLog "[DEBUG] System info: OS=$os_info, Runtime=$runtime_name $runtime_ver, Compose=$compose_ver"
    
    json_payload=$(cat <<EOF
{
  "anonymous_id": "$uuid",
  "event_type": "$event_type",
  "event_properties": {
    "start_time": "$start_time",
    "event_ver": "1",
    "script_type": "sh",
    "os": "$os_info",
    "container_runtime": "$runtime_name",
    "runtime_version": "$runtime_ver",
    "compose_version": "$compose_ver",
    "docker_version": "$runtime_ver",
    "docker_compose_version": "$compose_ver"
  }
}
EOF
)
```

- [ ] **Step 3: Syntax check**

```bash
bash -n opik.sh
```

- [ ] **Step 4: Commit**

```bash
git add opik.sh
git commit -m "feat(infra): make telemetry runtime-aware in opik.sh"
```

---

## Task 8: `opik.sh` — `--local-be-fe` host gateway and `print_usage` entry

**Files:**
- Modify: `opik.sh` line 669 and lines 249–250

- [ ] **Step 1: Fix `--local-be-fe` host gateway (line 669)**

```bash
# BEFORE
  export OPIK_REVERSE_PROXY_URL="http://host.docker.internal:8080"

# AFTER
  export OPIK_REVERSE_PROXY_URL="http://${OPIK_HOST_GATEWAY}:8080"
```

- [ ] **Step 2: Add `--runtime` to `print_usage` (after line 249)**

Find line 249 (`--guardrails    Enable guardrails...`). Insert after it:

```bash
  echo "  --runtime VALUE Set container runtime: docker or podman (default: auto-detect)"
```

- [ ] **Step 3: Verify no remaining hardcoded `host.docker.internal` in opik.sh**

```bash
grep -n "host\.docker\.internal" opik.sh
```

Expected: zero output.

- [ ] **Step 4: Syntax check and full verify**

```bash
bash -n opik.sh
./opik.sh --help | grep -i runtime
```

Expected second command: prints the `--runtime VALUE` help line.

- [ ] **Step 5: Commit**

```bash
git add opik.sh
git commit -m "feat(infra): use OPIK_HOST_GATEWAY in --local-be-fe; add --runtime to help in opik.sh"
```

---

## Task 9: `docker-compose.override.yaml` — parameterise host gateway

**Files:**
- Modify: `deployment/docker-compose/docker-compose.override.yaml` line 34

- [ ] **Step 1: Replace hardcoded hostname**

```yaml
# BEFORE
      OPIK_URL_OVERRIDE: ${OPIK_URL_OVERRIDE:-http://host.docker.internal:${OPIK_BACKEND_PORT:-8080}}

# AFTER
      OPIK_URL_OVERRIDE: ${OPIK_URL_OVERRIDE:-http://${OPIK_HOST_GATEWAY}:${OPIK_BACKEND_PORT:-8080}}
```

- [ ] **Step 2: Verify no remaining `host.docker.internal` in compose files**

```bash
grep -rn "host\.docker\.internal" deployment/
```

Expected: zero output.

- [ ] **Step 3: Commit**

```bash
git add deployment/docker-compose/docker-compose.override.yaml
git commit -m "feat(infra): parameterise host gateway in docker-compose.override.yaml"
```

---

## Task 10: (Conditional) `docker-compose.yaml` — fix non-standard volume syntax

**Skip this task if Task 1 Step 1 exited 0 with no warnings.**

**Files:**
- Modify: `deployment/docker-compose/docker-compose.yaml` lines 19, 57, 58

- [ ] **Step 1: Convert non-standard volume entries to standard short form**

```yaml
# BEFORE (line 19)
      - mysql:/var/lib/mysql/:type=volume,source=~/opik/mysql

# AFTER
      - mysql:/var/lib/mysql/
```

```yaml
# BEFORE (line 57)
      - clickhouse:/var/lib/clickhouse/:type=volume,source=~/opik/clickhouse/data

# AFTER
      - clickhouse:/var/lib/clickhouse/
```

```yaml
# BEFORE (line 58)
      - clickhouse-server:/var/log/clickhouse-server/:type=volume,source=~/opik/clickhouse/logs

# AFTER
      - clickhouse-server:/var/log/clickhouse-server/
```

- [ ] **Step 2: Re-run compatibility check**

```bash
podman compose -f deployment/docker-compose/docker-compose.yaml config
```

Expected: exit 0, no errors.

- [ ] **Step 3: Verify Docker still accepts the file**

```bash
docker compose -f deployment/docker-compose/docker-compose.yaml config >/dev/null
```

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add deployment/docker-compose/docker-compose.yaml
git commit -m "fix(infra): remove non-standard volume syntax for Podman Compose compatibility"
```

---

## Task 11: `opik.ps1` — full PowerShell runtime support

**Files:**
- Modify: `opik.ps1` — params, detection block, 6 functions, `Get-SystemInfo`, `Send-InstallReport`

This task has many sub-steps. Apply them in order; do a syntax check after each group.

- [ ] **Step 1: Add `-Runtime` parameter and `--runtime` option handler**

In `opik.ps1`, find the `param (` block at the top (line 3). Add `[string]$Runtime = ""` as the first parameter:

```powershell
param (
    [string]$Runtime = "",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$options = @()
)
```

- [ ] **Step 2: Insert runtime detection block after the param block**

Add the following immediately after the param block (before `[Console]::OutputEncoding = ...`):

```powershell
# Accept --runtime passed as bash-style double-dash in $options
$runtimeIdx = [array]::IndexOf([string[]]$options, "--runtime")
if ($runtimeIdx -ge 0 -and $runtimeIdx + 1 -lt $options.Count) {
    $Runtime = $options[$runtimeIdx + 1]
    $options = $options | Where-Object { $_ -ne "--runtime" -and $_ -ne $Runtime }
}

# Env var fallback
if (-not $Runtime) { $Runtime = $env:OPIK_CONTAINER_RUNTIME }

# Validate if explicitly set
if ($Runtime -and $Runtime -notin @("docker", "podman")) {
    Write-Host "[ERROR] Invalid -Runtime value: '$Runtime'. Must be 'docker' or 'podman'."
    exit 1
}

# Auto-detect
if (-not $Runtime) {
    docker info *>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $Runtime = "docker"
    } else {
        podman info *>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $Runtime = "podman"
        } else {
            Write-Host "[ERROR] Neither Docker nor Podman found. Please install one first."
            exit 1
        }
    }
}

$env:CONTAINER_RUNTIME = $Runtime

# Resolve compose binary and subcommand args
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
```

- [ ] **Step 3: Update `Get-DockerComposeCommand` (line 94)**

Replace line 95:
```powershell
# BEFORE
    $dockerArgs = @("compose", "-f", (Join-Path $dockerComposeDir "docker-compose.yaml"))

# AFTER
    $dockerArgs = $script:ComposeSubArgs + @("-f", (Join-Path $dockerComposeDir "docker-compose.yaml"))
```

- [ ] **Step 4: Update `Initialize-BuildxBake`**

Find `Initialize-BuildxBake` (around line 75). Add an early return for Podman at the start of the function:

```powershell
function Initialize-BuildxBake {
    if ($Runtime -eq "podman") {
        $env:COMPOSE_BAKE = "false"
        return
    }
    # ... rest of existing function unchanged ...
}
```

- [ ] **Step 5: Update `Test-DockerStatus`**

Replace lines 213–223:
```powershell
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
```

- [ ] **Step 6: Update all `docker inspect` and `docker logs` call sites**

There are 8 occurrences of `docker inspect` and 1 of `docker logs`. Replace each one:

```
Line 236:  docker inspect  →  & $Runtime inspect
Line 237:  docker inspect  →  & $Runtime inspect
Line 268:  docker inspect  →  & $Runtime inspect
Line 272:  docker inspect  →  & $Runtime inspect
Line 286:  docker logs     →  & $Runtime logs
Line 404:  docker inspect  →  & $Runtime inspect
Line 426:  docker @dockerArgs  →  & $script:ComposeBinary @dockerArgs
Line 438:  docker inspect  →  & $Runtime inspect
Line 439:  docker inspect  →  & $Runtime inspect
Line 544:  docker inspect  →  & $Runtime inspect
```

Use your editor's find-and-replace. After replacing, verify:

```powershell
# In PowerShell or bash:
grep -n "docker inspect\|docker logs\|docker @" opik.ps1
```

Expected: zero output.

- [ ] **Step 7: Update `Get-SystemInfo`**

Replace the `# Docker version` and `# Docker Compose version` sections (lines 140–187) with:

```powershell
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
```

- [ ] **Step 8: Update `Send-InstallReport` to use new `Get-SystemInfo` fields**

Find `Send-InstallReport` (around line 290). Update the section that uses `$SystemInfo` (around lines 339–365):

```powershell
        # BEFORE (error fallback)
        $SystemInfo = @{
            Os = "unknown"
            DockerVersion = "unknown"
            DockerComposeVersion = "unknown"
        }

        # AFTER (error fallback)
        $SystemInfo = @{
            Os = "unknown"
            RuntimeName = "unknown"
            RuntimeVersion = "unknown"
            ComposeVersion = "unknown"
            DockerVersion = "unknown"
            DockerComposeVersion = "unknown"
        }
```

And update the debug log line (~line 351):
```powershell
# BEFORE
        Write-DebugLog "[DEBUG] System info: OS=$($SystemInfo.Os), Docker=$($SystemInfo.DockerVersion), Docker Compose=$($SystemInfo.DockerComposeVersion)"

# AFTER
        Write-DebugLog "[DEBUG] System info: OS=$($SystemInfo.Os), Runtime=$($SystemInfo.RuntimeName) $($SystemInfo.RuntimeVersion), Compose=$($SystemInfo.ComposeVersion)"
```

And update the payload (lines 353–364):
```powershell
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
```

- [ ] **Step 9: Update `Show-Usage` — add `--runtime` entry**

Find `Show-Usage` (line 190). After the `--guardrails` line, insert:

```powershell
    Write-Host '  -Runtime VALUE    Set container runtime: docker or podman (default: auto-detect)'
    Write-Host '  --runtime VALUE   Same as -Runtime (bash-compatible form)'
```

- [ ] **Step 10: Fix `--local-be-fe` host gateway in opik.ps1**

Search for `host.docker.internal` in `opik.ps1`:

```bash
grep -n "host\.docker\.internal" opik.ps1
```

For each occurrence related to `--local-be-fe` / `OPIK_REVERSE_PROXY_URL`, replace `host.docker.internal` with `$env:OPIK_HOST_GATEWAY`.

- [ ] **Step 11: Verify no raw docker calls remain**

```bash
grep -n "^\s*docker " opik.ps1 | grep -v "#"
```

Expected: zero output.

- [ ] **Step 12: Commit**

```bash
git add opik.ps1
git commit -m "feat(infra): add Podman runtime support to opik.ps1"
```

---

## Task 12: `scripts/dev-runner.sh` — call `resolve_container_runtime` and fix `docker logs` hint

**Files:**
- Modify: `scripts/dev-runner.sh` lines 20–21 (after `init_worktree_ports`) and line 737

- [ ] **Step 1: Call `resolve_container_runtime` after `init_worktree_ports` (line 20)**

Find line 20 (`init_worktree_ports`). Add immediately after it:

```bash
resolve_container_runtime
```

- [ ] **Step 2: Fix `docker logs` hint (line 737)**

```bash
# BEFORE
    echo "  Frontend:         docker logs -f ${RESOURCE_PREFIX}-frontend-1"

# AFTER
    echo "  Frontend:         ${CONTAINER_RUNTIME} logs -f ${RESOURCE_PREFIX}-frontend-1"
```

- [ ] **Step 3: Verify no remaining raw `docker` calls**

```bash
grep -n "^\s*docker \|\"docker " scripts/dev-runner.sh | grep -v "#"
```

Expected: zero output (any remaining hits would be `docker` inside string literals like help text; inspect each one).

- [ ] **Step 4: Syntax check**

```bash
bash -n scripts/dev-runner.sh
```

- [ ] **Step 5: Commit**

```bash
git add scripts/dev-runner.sh
git commit -m "feat(infra): call resolve_container_runtime in dev-runner.sh; fix docker logs hint"
```

---

## Task 13: `local-development.mdx` — Podman prerequisites and docs

**Files:**
- Modify: `apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx`

- [ ] **Step 1: Update prerequisites section**

Find `### Required Tools` and update the Docker entry:

```markdown
# BEFORE
- **Docker** and **Docker Compose** - For running infrastructure services

# AFTER
- **Docker** and **Docker Compose** — OR — **Podman** (4.0+) with either `podman compose` (Podman 4.7+) or `podman-compose` (1.0+)
```

- [ ] **Step 2: Update the Verify Installation block**

After the existing Docker checks, add:

```markdown
# If using Podman instead of Docker
podman --version          # must be 4.0+
podman compose version    # if using built-in compose (Podman 4.7+)
podman-compose --version  # if using standalone (must be 1.0+)
```

- [ ] **Step 3: Document the `--runtime` flag**

Find the "Starting Opik in Docker" section. Add after the existing examples:

```markdown
# Use Podman instead of Docker (auto-detected if Docker is absent)
./opik.sh --runtime podman

# Force a specific runtime when both are installed
./opik.sh --runtime docker
OPIK_CONTAINER_RUNTIME=podman ./opik.sh
```

- [ ] **Step 4: Add rootless Podman troubleshooting note**

Find or create a `## Troubleshooting` section. Add:

```markdown
### `--local-be-fe` mode fails with rootless Podman

The `--local-be-fe` mode uses `host.containers.internal` to let the container reach your local backend. This requires Podman 4.0+ with Netavark networking (the default from Podman 4.0) or pasta networking (the default from Podman 5.0).

If you are on an older Podman with CNI networking, either upgrade Podman or start the container with `--network=slirp4netns:allow_host_loopback=true`.
```

- [ ] **Step 5: Commit**

```bash
git add apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx
git commit -m "docs: add Podman prerequisites and runtime flag documentation"
```

---

## Task 14: Final verification

- [ ] **Step 1: Confirm no remaining hardcoded `docker` runtime calls across all modified files**

```bash
grep -n "^\s*docker info\|^\s*docker inspect\|^\s*docker logs\|^\s*docker compose\|\"docker compose" \
  opik.sh scripts/dev-runner.sh deployment/docker-compose/docker-compose.override.yaml
```

Expected: zero output.

- [ ] **Step 2: Syntax check all bash files**

```bash
bash -n opik.sh && bash -n scripts/worktree-utils.sh && bash -n scripts/dev-runner.sh
echo "All syntax checks passed"
```

Expected: `All syntax checks passed`.

- [ ] **Step 3: Full Docker smoke test**

```bash
./opik.sh --help | grep -i runtime
./opik.sh --runtime docker --verify 2>&1 | head -5
```

Expected first command: shows `--runtime VALUE` in help.
Expected second command (if Docker is running with containers): shows container health status.

- [ ] **Step 4: Validate `--runtime` error handling**

```bash
./opik.sh --runtime foo 2>&1
echo "Exit: $?"
```

Expected: `❌ Invalid runtime 'foo'. Must be 'docker' or 'podman'.` + `Exit: 1`

```bash
./opik.sh --runtime 2>&1
echo "Exit: $?"
```

Expected: `❌ --runtime requires a value: docker or podman` + `Exit: 1`

- [ ] **Step 5: Verify OPIK_HOST_GATEWAY is set after script startup**

```bash
CONTAINER_RUNTIME=docker bash -c 'source scripts/worktree-utils.sh && resolve_container_runtime && echo "Gateway: $OPIK_HOST_GATEWAY"'
```

Expected: `Gateway: host.docker.internal`

- [ ] **Step 6: Open draft PR**

```bash
git push -u origin hellodk/NA-podman-runtime-support
gh pr create --draft \
  --title "[NA] [INFRA] feat: add Podman runtime support alongside Docker" \
  --body "$(cat <<'EOF'
## Summary

- Adds `resolve_container_runtime()` to `scripts/worktree-utils.sh` — auto-detects Docker or Podman, resolves compose command, sets `OPIK_HOST_GATEWAY`
- `opik.sh` gains a `--runtime docker|podman` flag (also reads `OPIK_CONTAINER_RUNTIME` env var); all `docker` call sites replaced with `$CONTAINER_RUNTIME`/`$COMPOSE_CMD`
- `opik.ps1` mirrors the same logic; accepts both `-Runtime` and `--runtime`
- `docker-compose.override.yaml` uses `${OPIK_HOST_GATEWAY}` instead of hardcoded `host.docker.internal`
- Telemetry adds `container_runtime`, `runtime_version`, `compose_version` fields
- Documentation updated with Podman prerequisites (min Podman 4.0+, podman-compose 1.0+) and troubleshooting

## Test plan

- [ ] `./opik.sh` auto-detects Docker on a Docker-only system
- [ ] `./opik.sh` auto-detects Podman on a Podman-only system (Podman 4.0+)
- [ ] `./opik.sh --runtime podman` forces Podman when both are present
- [ ] `./opik.sh --runtime foo` exits 1 with clear error
- [ ] `./opik.sh --runtime` (no value) exits 1 with clear error
- [ ] `OPIK_CONTAINER_RUNTIME=podman ./opik.sh` env var override works
- [ ] `./opik.sh --local-be-fe` uses `host.containers.internal` with Podman
- [ ] `./opik.sh --build` with Podman does not invoke buildx; `COMPOSE_BAKE` is false
- [ ] `opik.ps1 -Runtime podman` works on Windows with Podman Desktop
- [ ] `opik.ps1 --runtime podman` (double-dash style) also works
- [ ] Telemetry payload includes `container_runtime` field

> 🤖 Generated with [Claude Code](https://claude.ai/claude-code)
EOF
)"
```

---

## Minimum Version Requirements (for PR description and docs)

| Tool | Minimum | Reason |
|------|---------|--------|
| Podman | 4.0+ | Netavark networking → `host.containers.internal` |
| podman-compose (standalone) | 1.0+ | Docker Compose v2 naming convention |
| Podman built-in compose | Podman 4.7+ | `podman compose` subcommand |
| Podman Desktop (Win/macOS) | 1.0+ | `host.containers.internal` support |
