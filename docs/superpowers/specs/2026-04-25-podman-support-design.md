# Podman Support for Opik

**Date:** 2026-04-25  
**Status:** Approved (rev 2 — all audit issues resolved)  
**Scope:** `opik.sh`, `opik.ps1`, `scripts/dev-runner.sh`, `docker-compose.override.yaml`, `local-development.mdx`

---

## Problem

Opik's `opik.sh` and `opik.ps1` scripts hardcode `docker` and `docker compose` throughout. On RHEL, Fedora, CentOS, and other systems where Docker is not the default (or not installed at all), users cannot run Opik locally without manual workarounds. Podman is the natural alternative — it is API-compatible, rootless by default, and ships with `podman compose`.

---

## Goal

Allow users to run Opik with either Docker or Podman, with zero friction on systems where only one runtime is present.

---

## Minimum Version Requirements

| Tool | Minimum version | Reason |
|------|----------------|--------|
| Podman | 4.0+ | Netavark networking required for `host.containers.internal` |
| podman-compose (standalone) | 1.0+ | Docker Compose v2 container naming convention (`project-service-index`); older versions use underscores |
| Podman built-in compose | Podman 4.7+ | `podman compose` subcommand not available before 4.7 |
| Podman Desktop (Windows/macOS) | 1.0+ | `host.containers.internal` support |

These must be documented in the prerequisites section of `local-development.mdx`.

---

## Design

### Two New Variables

The design introduces two exported variables:

- **`CONTAINER_RUNTIME`** — `docker` or `podman`. Used for non-compose commands (`inspect`, `info`, `logs`).
- **`COMPOSE_CMD`** — the full compose invocation prefix (`docker compose`, `podman compose`, or `podman-compose`). Used in `get_docker_compose_cmd()`.

Keeping these separate handles the case where `podman-compose` is a standalone binary rather than a subcommand.

### Runtime & Compose Detection

Resolved in priority order (highest first):

1. `--runtime docker|podman` CLI flag (parsed with a `while/case` loop — see below)
2. `OPIK_CONTAINER_RUNTIME` environment variable
3. Auto-detection: `docker info` → `podman info` → fatal error

After `CONTAINER_RUNTIME` is set, resolve `COMPOSE_CMD`:

```bash
# Resolve COMPOSE_CMD after CONTAINER_RUNTIME is known
if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
  if podman compose version >/dev/null 2>&1; then
    COMPOSE_CMD="podman compose"
  elif command -v podman-compose >/dev/null 2>&1; then
    COMPOSE_CMD="podman-compose"
  else
    echo "❌ Podman found but no compose tool available."
    echo "   Option 1: Upgrade to Podman 4.7+ (includes 'podman compose')"
    echo "   Option 2: Install podman-compose: pip install podman-compose>=1.0"
    exit 1
  fi
else
  COMPOSE_CMD="docker compose"
fi

export CONTAINER_RUNTIME COMPOSE_CMD
```

Set `OPIK_HOST_GATEWAY` unconditionally at this point (not deferred to flag-specific branches):

```bash
if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
  export OPIK_HOST_GATEWAY="host.containers.internal"
else
  export OPIK_HOST_GATEWAY="host.docker.internal"
fi
```

### `--runtime` Flag Parsing (M1, M5 fixes)

Replace the fragile sed-based extraction with a proper loop. This block runs **before** all other flag parsing:

```bash
CONTAINER_RUNTIME="${OPIK_CONTAINER_RUNTIME:-}"
NEW_ARGS=()
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
    *) NEW_ARGS+=("$1"); shift ;;
  esac
done
set -- "${NEW_ARGS[@]}"

# Validate if explicitly set
if [[ -n "$CONTAINER_RUNTIME" && "$CONTAINER_RUNTIME" != "docker" && "$CONTAINER_RUNTIME" != "podman" ]]; then
  echo "❌ Invalid --runtime value: '$CONTAINER_RUNTIME'. Must be 'docker' or 'podman'."
  exit 1
fi

# Auto-detect if not set
if [[ -z "$CONTAINER_RUNTIME" ]]; then
  if docker info >/dev/null 2>&1; then
    CONTAINER_RUNTIME="docker"
  elif podman info >/dev/null 2>&1; then
    CONTAINER_RUNTIME="podman"
  else
    echo "❌ Neither Docker nor Podman is available. Please install one first."
    exit 1
  fi
fi
```

### `opik.sh` Call-Site Changes

| Function | Change |
|----------|--------|
| `check_docker_status()` → `check_runtime_status()` | `docker info` → `$CONTAINER_RUNTIME info` |
| `get_docker_compose_cmd()` | `docker compose` → `$COMPOSE_CMD` |
| `setup_buildx_bake()` | Add early return for Podman; explicitly `export COMPOSE_BAKE=false` (M2 fix) |
| `check_containers_status()` | `docker inspect` → `$CONTAINER_RUNTIME inspect` |
| `start_missing_containers()` | `docker inspect` → `$CONTAINER_RUNTIME inspect` |
| `wait_for_container_completion()` | `docker inspect` → `$CONTAINER_RUNTIME inspect`; `docker logs` → `$CONTAINER_RUNTIME logs` |
| `get_system_info()` | Runtime-aware version reporting (L1 fix — see Telemetry section) |
| `--local-be-fe` flag branch | Use `$OPIK_HOST_GATEWAY` (already set globally) instead of hardcoded hostname |
| `print_usage()` | Add `--runtime VALUE` entry |

**`setup_buildx_bake()` fix (M2):**

```bash
setup_buildx_bake() {
  if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
    export COMPOSE_BAKE=false   # explicit: prevents leaking a prior Docker session's COMPOSE_BAKE=true
    return
  fi
  # existing Docker buildx bake logic unchanged below
  if [[ "${BUILD_MODE}" = "true" ]]; then
    ...
  fi
}
```

### `docker-compose.override.yaml` Change (H1 fix)

`OPIK_HOST_GATEWAY` is always set by the shell before compose is invoked, so no `:-` fallback is needed in the compose file. This avoids nested variable expansion which compose does not support:

```yaml
# Before (broken — nested ${} not supported by compose)
OPIK_URL_OVERRIDE: ${OPIK_URL_OVERRIDE:-http://host.docker.internal:${OPIK_BACKEND_PORT:-8080}}

# After (OPIK_HOST_GATEWAY always set by opik.sh before compose runs)
OPIK_URL_OVERRIDE: ${OPIK_URL_OVERRIDE:-http://${OPIK_HOST_GATEWAY}:${OPIK_BACKEND_PORT:-8080}}
```

### Volume Mount Syntax Investigation (H2)

`docker-compose.yaml` contains:

```yaml
- mysql:/var/lib/mysql/:type=volume,source=~/opik/mysql
```

The `:type=volume,source=~/opik/mysql` suffix is non-standard in the Compose short-form volume spec. Docker likely silently ignores it (treating `mysql` as the named volume source). Podman Compose may parse it differently or error.

**Required action before implementation:** Run `podman compose config` against `docker-compose.yaml` on a Podman system to confirm whether this line is accepted or rejected. If rejected, convert to the standard long-form volume syntax:

```yaml
volumes:
  - type: volume
    source: mysql
    target: /var/lib/mysql/
```

This is a required pre-implementation verification step — the compose file change may or may not be needed depending on the result.

### Container Naming Convention (H3 fix)

Docker Compose v2 and `podman-compose` >= 1.0 both name containers as `<project>-<service>-<index>` (hyphens). The existing `INFRA_CONTAINERS` arrays in `opik.sh` and `opik.ps1` use this format and are therefore compatible, **provided** the user has `podman-compose` >= 1.0 or Podman 4.7+ built-in compose.

Older `podman-compose` (< 1.0) used underscores (`<project>_<service>_<index>`). The minimum version requirement (1.0+) documented above enforces compatibility. No code change needed; this is a documentation and version-gate concern only.

### `scripts/dev-runner.sh` Change (M4 fix)

Line 737 hardcodes `docker logs` in a user-facing status hint:

```bash
# Before
echo "  Frontend:         docker logs -f ${RESOURCE_PREFIX}-frontend-1"

# After
echo "  Frontend:         ${CONTAINER_RUNTIME} logs -f ${RESOURCE_PREFIX}-frontend-1"
```

`dev-runner.sh` already sources `worktree-utils.sh` and delegates container operations to `opik.sh`. However, for `CONTAINER_RUNTIME` to be available in `dev-runner.sh`, it must either:
- Re-run the same detection logic (duplicated), or
- Export `CONTAINER_RUNTIME` from `opik.sh` calls and propagate it

Since `dev-runner.sh` calls `opik.sh` as a subprocess, `CONTAINER_RUNTIME` won't be inherited from that subprocess back to the parent. The fix is to add the same detection logic (runtime detection block) to the top of `dev-runner.sh` as well, or extract it into `scripts/worktree-utils.sh` so both scripts source it.

**Decision:** Extract the detection block into `scripts/worktree-utils.sh` as a `resolve_container_runtime()` function called by `init_worktree_ports()`. Both `opik.sh` and `dev-runner.sh` already source `worktree-utils.sh`, so both get `CONTAINER_RUNTIME` and `COMPOSE_CMD` set automatically.

This makes `scripts/worktree-utils.sh` also a changed file.

### `opik.ps1` Changes

**Runtime detection (L2 fix — accept both `-Runtime` and `--runtime` style):**

```powershell
param (
    [string]$Runtime = "",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$options = @()
)

# Also accept --runtime passed as part of $options (bash-style habit)
$runtimeIdx = [array]::IndexOf($options, "--runtime")
if ($runtimeIdx -ge 0 -and $runtimeIdx + 1 -lt $options.Count) {
    $Runtime = $options[$runtimeIdx + 1]
    $options = $options | Where-Object { $_ -ne "--runtime" -and $_ -ne $Runtime }
}

# Env var fallback
if (-not $Runtime) { $Runtime = $env:OPIK_CONTAINER_RUNTIME }

# Validate if set
if ($Runtime -and $Runtime -notin @("docker", "podman")) {
    Write-Error "❌ Invalid -Runtime value: '$Runtime'. Must be 'docker' or 'podman'."
    exit 1
}

# Auto-detect
if (-not $Runtime) {
    docker info *>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { $Runtime = "docker" }
    else {
        podman info *>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $Runtime = "podman" }
        else { Write-Error "❌ Neither Docker nor Podman found."; exit 1 }
    }
}

$env:CONTAINER_RUNTIME = $Runtime

# Resolve compose command
if ($Runtime -eq "podman") {
    $env:OPIK_HOST_GATEWAY = "host.containers.internal"
    podman compose version *>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $ComposeCmd = "podman compose"
    } elseif (Get-Command podman-compose -ErrorAction SilentlyContinue) {
        $ComposeCmd = "podman-compose"
    } else {
        Write-Error "❌ Podman found but no compose tool available. Install podman-compose or upgrade to Podman 4.7+"
        exit 1
    }
} else {
    $env:OPIK_HOST_GATEWAY = "host.docker.internal"
    $ComposeCmd = "docker compose"
}

# Buildx bake: disable explicitly for Podman (M2 fix)
if ($Runtime -eq "podman") {
    $env:COMPOSE_BAKE = "false"
}
```

`Get-DockerComposeCommand` uses `$ComposeCmd` instead of hardcoded `docker compose`.  
All `docker inspect` calls replaced with `& $Runtime inspect`.  
All `docker logs` calls replaced with `& $Runtime logs`.

### Telemetry (L1 fix)

`send_install_report` currently sends `docker_version` and `docker_compose_version` fields. Add a `container_runtime` field and rename the version fields to be runtime-neutral. Keep the old field names for backwards compatibility with existing analytics dashboards, but populate them with the actual runtime values:

```bash
get_system_info() {
  # ... OS detection unchanged ...

  local runtime_version="unknown"
  local compose_version="unknown"

  if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
    runtime_version=$(podman --version 2>/dev/null | sed -n 's/podman version \(.*\)/\1/p' || echo "unknown")
    compose_version=$($COMPOSE_CMD version --short 2>/dev/null || echo "unknown")
  else
    runtime_version=$(docker --version 2>/dev/null | sed -n 's/Docker version \([^,]*\).*/\1/p' || echo "unknown")
    compose_version=$(docker compose version 2>/dev/null | sed -n 's/Docker Compose version \(.*\)$/\1/p' || echo "unknown")
  fi

  printf "%s\t%s\t%s\t%s" "$os_info" "$runtime_version" "$compose_version" "$CONTAINER_RUNTIME"
}
```

Add `container_runtime` to the JSON payload in `send_install_report`. The fields previously named `docker_version` / `docker_compose_version` are renamed to `runtime_version` / `compose_version` in the payload (schema change — confirm with maintainers if telemetry schema is locked).

### Host Gateway on Windows/macOS (M3 fix)

`host.containers.internal` is supported across all platforms with Podman 4.0+:
- Linux rootless (Netavark, pasta, slirp4netns): supported
- macOS (Podman Desktop 1.0+): supported
- Windows (Podman Desktop 1.0+): supported

No platform-specific branching is needed beyond the single `OPIK_HOST_GATEWAY` variable. The minimum version requirement (Podman 4.0+) gates this.

### Rootless Networking Note (L3 — documentation)

For `--local-be-fe` mode with rootless Podman on Linux, the container must reach `host.containers.internal`. This requires:
- Podman 4.0+ with Netavark (default network driver from 4.0)
- OR Podman with `pasta` (default from 5.0) or `slirp4netns`

Users on very old Podman with CNI networking may need `--network=slirp4netns:allow_host_loopback=true` or to upgrade. Document this as a troubleshooting note in `local-development.mdx`.

---

## Files Changed

| File | Type | Change summary |
|------|------|---------------|
| `opik.sh` | Core | Runtime detection, `COMPOSE_CMD`, 7 call-site substitutions, host gateway, validation, help text |
| `opik.ps1` | Core | Mirror of opik.sh; accepts both `-Runtime` and `--runtime` |
| `scripts/worktree-utils.sh` | Shared | Extract `resolve_container_runtime()` called by `init_worktree_ports()` |
| `scripts/dev-runner.sh` | Minor | Replace `docker logs` hint; benefits from shared detection |
| `deployment/docker-compose/docker-compose.override.yaml` | Config | Use `${OPIK_HOST_GATEWAY}` (always set); remove nested `:-` fallback |
| `deployment/docker-compose/docker-compose.yaml` | **Conditional** | Convert non-standard volume syntax to long-form IF `podman compose config` validation fails |
| `apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx` | Docs | Podman prerequisites, version requirements, `--runtime` flag, troubleshooting note |

## Files Not Changed

| File | Reason |
|------|--------|
| `deployment/docker-compose/docker-compose.local-be*.yaml` | Standard Compose spec; Podman supports it |

---

## Pre-Implementation Verification

Before writing the implementation plan, verify on a Podman system:

```bash
# Validate compose file parses cleanly
podman compose -f deployment/docker-compose/docker-compose.yaml config
```

Result determines whether `docker-compose.yaml` needs the volume syntax conversion (H2). If the command exits 0 with no warnings about the volume line, no change needed. If it errors or warns, apply the long-form volume fix.

---

## Testing Checklist

- [ ] `./opik.sh` auto-detects Docker on a Docker-only system
- [ ] `./opik.sh` auto-detects Podman on a Podman-only system (Podman 4.0+, podman-compose 1.0+)
- [ ] `./opik.sh --runtime podman` forces Podman when both are present
- [ ] `./opik.sh --runtime foo` prints a clear error and exits 1
- [ ] `./opik.sh --runtime` (no value) prints a clear error and exits 1
- [ ] `OPIK_CONTAINER_RUNTIME=podman ./opik.sh` env var override works
- [ ] `./opik.sh --local-be-fe` sets `host.containers.internal` for Podman
- [ ] `./opik.sh --build` with Podman builds images without invoking buildx
- [ ] `COMPOSE_BAKE` is not `true` after running with Podman (even if previously set)
- [ ] `./opik.sh --verify` correctly inspects containers via Podman
- [ ] `./opik.sh --stop` correctly stops containers via Podman
- [ ] Container names match `${COMPOSE_PROJECT_NAME}-<service>-1` pattern with Podman
- [ ] `opik.ps1 -Runtime podman` works on Windows with Podman Desktop
- [ ] `opik.ps1 --runtime podman` (double-dash style) also works
- [ ] `./opik.sh --help` shows the `--runtime` flag
- [ ] Telemetry payload includes `container_runtime` field when using Podman
- [ ] `podman compose config` passes on `docker-compose.yaml` (pre-impl verification)

---

## PR Convention

Branch: `hellodk/NA-podman-runtime-support`  
Commit prefix: `[NA] [INFRA] feat: add Podman runtime support alongside Docker`
