# Podman Support for Opik

**Date:** 2026-04-25  
**Status:** Approved  
**Scope:** `opik.sh`, `opik.ps1`, `docker-compose.override.yaml`, `local-development.mdx`

---

## Problem

Opik's `opik.sh` and `opik.ps1` scripts hardcode `docker` and `docker compose` throughout. On RHEL, Fedora, CentOS, and other systems where Docker is not the default (or not installed at all), users cannot run Opik locally without manual workarounds. Podman is the natural alternative — it is API-compatible, rootless by default, and ships with `podman compose`.

---

## Goal

Allow users to run Opik with either Docker or Podman, with zero friction on systems where only one runtime is present.

---

## Design

### Runtime Selection

Priority order (highest first):

1. `--runtime docker|podman` CLI flag
2. `OPIK_CONTAINER_RUNTIME` environment variable
3. Auto-detection: probe `docker info`, fall back to `podman info`
4. Fatal error if neither is found

The resolved value is stored in `CONTAINER_RUNTIME` and exported for child processes.

### `opik.sh` Changes

**New flag parsing** (inserted before other flag parsing):

```bash
CONTAINER_RUNTIME=""

if [[ "$*" == *"--runtime"* ]]; then
  CONTAINER_RUNTIME=$(echo "$*" | sed -n 's/.*--runtime \([^ ]*\).*/\1/p')
  set -- ${@/--runtime $CONTAINER_RUNTIME/}
fi

if [[ -z "$CONTAINER_RUNTIME" ]]; then
  CONTAINER_RUNTIME="${OPIK_CONTAINER_RUNTIME:-}"
fi

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

export CONTAINER_RUNTIME
```

**Six call-site changes:**

| Function | Change |
|----------|--------|
| `check_docker_status()` → `check_runtime_status()` | `docker info` → `$CONTAINER_RUNTIME info` |
| `get_docker_compose_cmd()` | `docker compose` → `$CONTAINER_RUNTIME compose` |
| `setup_buildx_bake()` | Wrap entire body in `if [[ "$CONTAINER_RUNTIME" == "docker" ]]` |
| `check_containers_status()` | `docker inspect` → `$CONTAINER_RUNTIME inspect` |
| `start_missing_containers()` | `docker inspect` → `$CONTAINER_RUNTIME inspect` |
| `wait_for_container_completion()` | `docker inspect` → `$CONTAINER_RUNTIME inspect`; `docker logs` → `$CONTAINER_RUNTIME logs` |
| `get_system_info()` | Report runtime name and version (runtime-aware version string parsing) |

**Host gateway fix** (in `--local-be-fe` flag handling):

```bash
if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
  export OPIK_REVERSE_PROXY_URL="http://host.containers.internal:8080"
  export OPIK_HOST_GATEWAY="host.containers.internal"
else
  export OPIK_REVERSE_PROXY_URL="http://host.docker.internal:8080"
  export OPIK_HOST_GATEWAY="host.docker.internal"
fi
```

`OPIK_HOST_GATEWAY` is also exported in non-local-be-fe paths so the override compose file can consume it.

**`--help` output** — add to `print_usage()`:

```
  --runtime VALUE     Override container runtime: docker or podman (default: auto-detect)
```

### `docker-compose.override.yaml` Change

Parameterize `host.docker.internal` so it works with both runtimes:

```yaml
# Before
OPIK_URL_OVERRIDE: ${OPIK_URL_OVERRIDE:-http://host.docker.internal:${OPIK_BACKEND_PORT:-8080}}

# After
OPIK_URL_OVERRIDE: ${OPIK_URL_OVERRIDE:-http://${OPIK_HOST_GATEWAY:-host.docker.internal}:${OPIK_BACKEND_PORT:-8080}}
```

### `opik.ps1` Changes

Mirror of `opik.sh` changes in PowerShell:

**New parameter:**

```powershell
param (
    [string]$Runtime = "",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$options = @()
)
```

**Detection block:**

```powershell
if (-not $Runtime) { $Runtime = $env:OPIK_CONTAINER_RUNTIME }
if (-not $Runtime) {
    docker info *>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { $Runtime = "docker" }
    else {
        podman info *>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $Runtime = "podman" }
        else { Write-Error "Neither Docker nor Podman found."; exit 1 }
    }
}
$env:CONTAINER_RUNTIME = $Runtime
```

All `docker compose` calls in `Get-DockerComposeCommand` replaced with `& $Runtime compose`.  
`Initialize-BuildxBake` becomes a no-op when `$Runtime -eq "podman"`.  
All `docker inspect` calls replaced with `& $Runtime inspect`.  
Host gateway parameterized: `$hostGateway = if ($Runtime -eq "podman") { "host.containers.internal" } else { "host.docker.internal" }`.

### Documentation

`apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx`:

- Prerequisites: change "Docker and Docker Compose" to "Docker or Podman (with `podman compose`)"
- Add Podman verification commands alongside Docker ones
- Note that `--runtime podman` can be passed explicitly

---

## Files Changed

| File | Type |
|------|------|
| `opik.sh` | Core — runtime detection + 6 call-site substitutions |
| `opik.ps1` | Core — mirror of opik.sh in PowerShell |
| `deployment/docker-compose/docker-compose.override.yaml` | Parameterize `OPIK_HOST_GATEWAY` |
| `apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx` | Docs — mention Podman |

## Files Not Changed

| File | Reason |
|------|--------|
| `scripts/worktree-utils.sh` | No direct container runtime calls; port/naming logic is runtime-agnostic |
| `deployment/docker-compose/docker-compose.yaml` | Standard Compose spec; Podman supports it |
| `deployment/docker-compose/docker-compose.local-be*.yaml` | Same |

---

## Testing Checklist

- [ ] `./opik.sh` auto-detects Docker on a Docker-only system
- [ ] `./opik.sh` auto-detects Podman on a Podman-only system
- [ ] `./opik.sh --runtime podman` forces Podman when both are present
- [ ] `OPIK_CONTAINER_RUNTIME=podman ./opik.sh` env var override works
- [ ] `./opik.sh --local-be-fe` sets `host.containers.internal` with Podman
- [ ] `./opik.sh --verify` correctly inspects containers via Podman
- [ ] `./opik.sh --stop` correctly stops containers via Podman
- [ ] `opik.ps1 -Runtime podman` works on Windows with Podman Desktop
- [ ] `./opik.sh --help` shows the `--runtime` flag

---

## PR Convention

Branch: `hellodk/NA-podman-runtime-support`  
Commit prefix: `[NA] [INFRA] feat: add Podman runtime support alongside Docker`
