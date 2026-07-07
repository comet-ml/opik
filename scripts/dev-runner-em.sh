#!/usr/bin/env bash
# Comet EM/Platform stack (comet-backend ReactWebappServerApplication + comet-react
# + single-origin nginx proxy) for dev-runner.sh.
#
# This file is SOURCED by dev-runner.sh (not executed) to keep the core script
# focused on the Opik dev flow. It defines the EM_* variables, sibling-repo
# auto-detection, and every em_*/*_em_* function. dev-runner.sh only calls a
# handful of hooks (start_em_stack/stop_em_stack/em_print_status/…), all gated
# on PLATFORM_ENABLED=true, so with the flag unset this is inert.
#
# Relies on helpers/vars defined in dev-runner.sh before it is sourced:
# log_*/require_command/get_descendants, the color vars, PROJECT_ROOT, and the
# worktree ports (PORT_OFFSET, RESOURCE_PREFIX, BACKEND_PORT, FRONTEND_PORT,
# MYSQL_PORT, REDIS_PORT, MINIO_API_PORT). All are used only inside functions
# (resolved at call time), so source order within dev-runner.sh is not fragile.

# ---- Variables ----
# --- Comet EM stack: comet-backend (ReactWebappServerApplication) + comet-react ---
# Opik-team only. Opt-in via PLATFORM_ENABLED=true: brings up the EM/Platform
# backend + frontend alongside Opik, reusing Opik's dev MySQL/Redis/MinIO
# (no comet-helm-mini needed). comet-backend and comet-react are auto-detected
# as siblings of the opik repo (see the detect blocks below); override with
# COMET_BACKEND_PATH / COMET_REACT_PATH. Unlike ollie, this is NOT auto-enabled
# on mere sibling presence — it triggers a heavy full-reactor Maven build plus a
# second webpack dev server, so it stays off unless PLATFORM_ENABLED=true.
PLATFORM_ENABLED="${PLATFORM_ENABLED:-false}"
# Ports offset per worktree, clear of Opik's 8080/8081/5173/5174.
EM_BACKEND_PORT="${EM_BACKEND_PORT:-$((8200 + PORT_OFFSET))}"
EM_BACKEND_ADMIN_PORT="${EM_BACKEND_ADMIN_PORT:-$((8201 + PORT_OFFSET))}"
EM_FRONTEND_PORT="${EM_FRONTEND_PORT:-$((8300 + PORT_OFFSET))}"
EM_BACKEND_PID_FILE="/tmp/${RESOURCE_PREFIX}-em-backend.pid"
EM_BACKEND_LOG_FILE="/tmp/${RESOURCE_PREFIX}-em-backend.log"
# Sidecar so --stop can find the repo even without COMET_BACKEND_PATH in scope.
EM_BACKEND_REPO_PATH_FILE="/tmp/${RESOURCE_PREFIX}-em-backend.repo"
# Generated Dropwizard config (patched copy of the module's test-config.yml).
EM_BACKEND_CONFIG_FILE="/tmp/${RESOURCE_PREFIX}-em-backend-config.yml"
EM_FRONTEND_PID_FILE="/tmp/${RESOURCE_PREFIX}-em-frontend.pid"
EM_FRONTEND_LOG_FILE="/tmp/${RESOURCE_PREFIX}-em-frontend.log"
EM_FRONTEND_REPO_PATH_FILE="/tmp/${RESOURCE_PREFIX}-em-frontend.repo"
# Backup of the developer's hand-maintained comet-react public/config.js, so
# --stop can restore it after dev-runner points the FE at the EM backend.
EM_FRONTEND_CONFIG_BAK_FILE="/tmp/${RESOURCE_PREFIX}-em-frontend-config.js.bak"
# Single-origin reverse proxy (nginx container) fronting EM + Opik. Comet mode's
# URLs are all relative (/, /api, /opik, /opik/api), so the whole integrated UI
# must be served from ONE origin — this is the URL you open in the browser.
EM_PROXY_PORT="${EM_PROXY_PORT:-$((9100 + PORT_OFFSET))}"
EM_PROXY_CONTAINER="${RESOURCE_PREFIX}-em-proxy"
EM_PROXY_CONF="/tmp/${RESOURCE_PREFIX}-em-proxy.conf"

# ---- Sibling-repo auto-detection ----
# Auto-detect sibling comet-backend / comet-react checkouts for the EM stack
# (mirrors the conventions above). Only consulted when PLATFORM_ENABLED=true;
# setting COMET_BACKEND_PATH="" / COMET_REACT_PATH="" opts a repo out.
if [ -z "${COMET_BACKEND_PATH+set}" ]; then
    _cb_candidate="$(cd "$PROJECT_ROOT/.." 2>/dev/null && pwd)/comet-backend"
    if [ -d "$_cb_candidate" ] && [ -f "$_cb_candidate/comet-ml-react-webapp/pom.xml" ]; then
        COMET_BACKEND_PATH="$_cb_candidate"
    fi
    unset _cb_candidate
fi
if [ -z "${COMET_REACT_PATH+set}" ]; then
    _cr_candidate="$(cd "$PROJECT_ROOT/.." 2>/dev/null && pwd)/comet-react"
    if [ -d "$_cr_candidate" ] && [ -f "$_cr_candidate/package.json" ]; then
        COMET_REACT_PATH="$_cr_candidate"
    fi
    unset _cr_candidate
fi


# ---- Functions ----
# --- Comet EM stack (comet-backend ReactWebappServerApplication + comet-react) ---
# Opt-in via PLATFORM_ENABLED=true. Reuses Opik's dev MySQL/Redis/MinIO. All
# runtime state (config, pid, logs) lives under /tmp/${RESOURCE_PREFIX}-em-*;
# the only writes into the sibling repos are Maven's own target/ and the
# gitignored comet-react public/config.js (backed up + restored on --stop).

# Is the EM backend opted in and available?
em_stack_enabled() {
    [ "$PLATFORM_ENABLED" = "true" ] && [ -n "${COMET_BACKEND_PATH:-}" ]
}

# Is the EM frontend (comet-react) also available? Requires the backend gate.
em_frontend_enabled() {
    em_stack_enabled && [ -n "${COMET_REACT_PATH:-}" ]
}

# Feature (major) version of the JDK at $1 (a JAVA_HOME dir); echoes nothing if
# it's not a usable JDK. Handles both "17.0.15" and legacy "1.8.0_x" formats.
_java_major_of() {
    local jbin="$1/bin/java" line
    [ -x "$jbin" ] || return 0
    line=$("$jbin" -version 2>&1 | head -1)
    if [[ "$line" =~ \"([0-9]+)(\.([0-9]+))? ]]; then
        if [ "${BASH_REMATCH[1]}" = "1" ]; then echo "${BASH_REMATCH[3]}"; else echo "${BASH_REMATCH[1]}"; fi
    fi
}

# JDK the EM stack must build and run under. comet-backend targets Java 17 with
# Lombok 1.18.30 (works on 17/21, NOT newer), whereas opik-backend requires JDK
# 25 — so the two can't share JAVA_HOME, and we must NOT assume the user's
# default JDK suits comet-backend (most Opik devs default to 25). Resolution:
# explicit EM_JAVA_HOME wins; else find an installed JDK whose major is one of
# EM_JAVA_ACCEPTED_MAJORS (default "17 21", tried in order) across macOS
# java_home, the ambient JAVA_HOME, SDKMAN, Linux, and Homebrew. Echoes nothing
# if none is found (callers warn + skip the EM stack).
em_java_home() {
    if [ -n "${EM_JAVA_HOME:-}" ]; then
        echo "$EM_JAVA_HOME"
        return
    fi
    local want cand
    for want in ${EM_JAVA_ACCEPTED_MAJORS:-17 21}; do
        # macOS: any installed JDK of this major, regardless of the default.
        # NB: java_home falls back to the newest JDK for an unavailable version
        # (exit 0), so verify the resolved major actually matches.
        if [ -x /usr/libexec/java_home ]; then
            cand=$(/usr/libexec/java_home -v "$want" 2>/dev/null || true)
            if [ -n "$cand" ] && [ "$(_java_major_of "$cand")" = "$want" ]; then echo "$cand"; return; fi
        fi
        # The ambient JAVA_HOME, only if it already is this major
        if [ -n "${JAVA_HOME:-}" ] && [ "$(_java_major_of "$JAVA_HOME")" = "$want" ]; then
            echo "$JAVA_HOME"; return
        fi
        # Common install roots (SDKMAN, Linux distros, Homebrew, macOS bundles)
        for cand in \
            "$HOME/.sdkman/candidates/java/"*"$want"*/ \
            /usr/lib/jvm/*"$want"*/ \
            /opt/homebrew/opt/openjdk@"$want" \
            /opt/homebrew/opt/openjdk@"$want"/libexec/openjdk.jdk/Contents/Home \
            /usr/local/opt/openjdk@"$want" \
            /Library/Java/JavaVirtualMachines/*"$want"*/Contents/Home \
            "$HOME/Library/Java/JavaVirtualMachines/"*"$want"*/Contents/Home ; do
            cand="${cand%/}"
            if [ -x "$cand/bin/java" ] && [ "$(_java_major_of "$cand")" = "$want" ]; then echo "$cand"; return; fi
        done
    done
}

em_backend_running() {
    [ -f "$EM_BACKEND_PID_FILE" ] && kill -0 "$(cat "$EM_BACKEND_PID_FILE")" 2>/dev/null
}

# Is the EM backend serving? Liveness probe, NOT deep health: /isAlive/ping is
# Dropwizard's aggregate health endpoint and returns 500 if any check is red —
# locally the feature-toggle check is permanently red (no ci-feature-toggles.json,
# toggles server unreachable), so /isAlive/ping would never go green and the
# readiness wait would time out. /auth/test returns 200 as soon as the app is
# serving, independent of feature toggles. Also detects an instance started
# outside dev-runner (e.g. from IntelliJ) so we reuse it instead of colliding.
em_backend_healthy() {
    command -v curl >/dev/null 2>&1 || return 1
    curl -sf --max-time 2 "http://localhost:${EM_BACKEND_PORT}/auth/test" >/dev/null 2>&1
}

em_frontend_running() {
    [ -f "$EM_FRONTEND_PID_FILE" ] && kill -0 "$(cat "$EM_FRONTEND_PID_FILE")" 2>/dev/null
}

wait_for_em_backend_ready() {
    require_command curl
    local pid="${1:-}"
    log_info "Waiting for EM backend to be ready on port ${EM_BACKEND_PORT}..."
    # First boot runs schema migrations against the fresh 'logger' DB, so allow
    # a generous window.
    local max_wait=180
    local count=0
    while [ $count -lt $max_wait ]; do
        if em_backend_healthy; then
            log_success "EM backend is ready and accepting connections"
            log_info "EM backend API: ${GREEN}http://localhost:${EM_BACKEND_PORT}${NC}"
            return 0
        fi
        sleep 1
        count=$((count + 1))
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            log_error "EM backend process died while waiting for it to be ready"
            log_error "Check logs: tail -f $EM_BACKEND_LOG_FILE"
            rm -f "$EM_BACKEND_PID_FILE"
            return 1
        fi
    done
    log_error "EM backend failed to become ready after ${max_wait}s"
    log_error "Check logs: tail -f $EM_BACKEND_LOG_FILE"
    return 1
}

# Locate the shaded react-webapp jar (mirrors find_jar_files for opik-backend).
find_em_backend_jar() {
    local dir="$COMET_BACKEND_PATH/comet-ml-react-webapp/target"
    local jars=()
    while IFS= read -r -d '' j; do
        jars+=("$j")
    done < <(find "$dir" -maxdepth 1 -type f -name 'comet-ml-react-webapp-*.jar' \
                ! -name '*original*' ! -name '*sources*' ! -name '*javadoc*' -print0 2>/dev/null)
    if [ "${#jars[@]}" -eq 0 ]; then
        return 1
    fi
    EM_BACKEND_JAR=$(printf '%s\n' "${jars[@]}" | sort -V | tail -n 1)
    return 0
}

build_em_backend() {
    require_command mvn
    local em_jh
    em_jh=$(em_java_home)
    if [ -z "$em_jh" ] || [ ! -x "$em_jh/bin/java" ]; then
        log_error "EM stack needs a JDK 17 for comet-backend (opik-backend uses JDK 25; they can't share one)."
        log_error "Install a JDK 17 or set EM_JAVA_HOME=/path/to/jdk17, then retry."
        return 1
    fi
    log_info "Building comet-backend React webapp with JDK $(_java_major_of "$em_jh") at $em_jh (EM reactor; first run is slow)..."
    if ( cd "$COMET_BACKEND_PATH" && JAVA_HOME="$em_jh" \
         mvn -pl comet-ml-react-webapp -am clean install \
             -T 1C -Dmaven.test.skip=true -Dspotless.skip=true \
             -Dmaven.javadoc.skip=true -Dmaven.source.skip=true ); then
        if ! find_em_backend_jar; then
            log_error "comet-backend build finished but no react-webapp JAR was found"
            return 1
        fi
        log_success "comet-backend React webapp built: $EM_BACKEND_JAR"
    else
        log_error "comet-backend build failed"
        return 1
    fi
}

# Create the 'logger' database + user/user-ro accounts on Opik's dev MySQL.
# ReactWebappServerApplication self-migrates its schema on startup, so it only
# needs the DB + accounts to exist (mirrors comet-mini's init_sql). Runs as the
# opik root user (root/opik) against the dev-runner MySQL.
provision_em_backend_mysql() {
    require_command mysql
    log_info "Provisioning EM 'logger' database + users on Opik MySQL (localhost:${MYSQL_PORT})..."
    if mysql -h 127.0.0.1 -P "${MYSQL_PORT}" -u root -popik --connect-timeout=10 <<'SQL'
CREATE DATABASE IF NOT EXISTS `logger` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- comet-backend's Liquibase creates TRIGGERs/functions; Opik's MySQL has binlog
-- on and the 'user' account isn't SUPER, so allow non-super creators (what
-- comet-mini's MySQL config does). Set as root; resets on MySQL restart, and
-- this provisioning runs on every EM start so it's reapplied.
SET GLOBAL log_bin_trust_function_creators = 1;
CREATE USER IF NOT EXISTS 'user'@'%' IDENTIFIED BY 'pass';
CREATE USER IF NOT EXISTS 'user-ro'@'%' IDENTIFIED BY 'pass';
GRANT ALL PRIVILEGES ON `logger`.* TO 'user'@'%';
GRANT SELECT ON `logger`.* TO 'user-ro'@'%';
FLUSH PRIVILEGES;
SQL
    then
        log_success "EM 'logger' database ready"
    else
        log_error "Failed to provision EM 'logger' database on Opik MySQL"
        return 1
    fi
}

# Patch the module's test-config.yml into a runtime config that works against
# Opik's infra. Only two values aren't ${..}-overridable and need rewriting:
#   - redisConfig.redisPass is hardcoded 'NA'; Opik's Redis needs 'opik'
#   - the S3 endpoint port is hardcoded ':9000'; Opik's MinIO API is on
#     ${MINIO_API_PORT} (the :9000 rewrite is scoped to MINIO_HOST lines)
# Everything else is env-substituted from start_em_backend_local.
generate_em_backend_config() {
    local src="$COMET_BACKEND_PATH/comet-ml-react-webapp/src/test/resources/test-config.yml"
    if [ ! -f "$src" ]; then
        log_error "EM backend config template not found: $src"
        return 1
    fi
    sed -E \
        -e 's/redisPass: NA/redisPass: opik/g' \
        -e "/MINIO_HOST/ s/:9000/:${MINIO_API_PORT}/g" \
        "$src" > "$EM_BACKEND_CONFIG_FILE"
    log_debug "Generated EM backend config: $EM_BACKEND_CONFIG_FILE"
}

start_em_backend_local() {
    if ! em_stack_enabled; then
        return 0
    fi
    local em_jh
    em_jh=$(em_java_home)
    if [ -z "$em_jh" ] || [ ! -x "$em_jh/bin/java" ]; then
        log_warning "EM stack needs a JDK 17 for comet-backend (opik uses JDK 25); none found."
        log_warning "Set EM_JAVA_HOME=/path/to/jdk17 and retry. Skipping EM backend."
        return 1
    fi

    if [ ! -d "$COMET_BACKEND_PATH" ]; then
        log_warning "COMET_BACKEND_PATH points to a non-existent directory: $COMET_BACKEND_PATH"
        log_warning "Skipping EM backend startup"
        return 1
    fi

    # Reuse a healthy EM backend started outside dev-runner (e.g. IntelliJ).
    if em_backend_healthy; then
        log_success "EM backend already healthy on port ${EM_BACKEND_PORT} — reusing existing instance"
        # Clear any stale PID so --stop won't try to kill the reused instance.
        rm -f "$EM_BACKEND_PID_FILE" "$EM_BACKEND_REPO_PATH_FILE"
        return 0
    fi
    if em_backend_running; then
        log_warning "EM backend is already running (PID: $(cat "$EM_BACKEND_PID_FILE"))"
        return 0
    fi
    rm -f "$EM_BACKEND_PID_FILE"

    if ! find_em_backend_jar; then
        log_warning "No EM backend JAR found in target/. Building comet-backend automatically..."
        build_em_backend || { log_warning "Continuing without EM backend"; return 1; }
    fi

    provision_em_backend_mysql || { log_warning "Continuing without EM backend"; return 1; }
    generate_em_backend_config || return 1

    log_info "Starting comet-backend ReactWebappServerApplication on port ${EM_BACKEND_PORT}..."
    (
        cd "$COMET_BACKEND_PATH/comet-ml-react-webapp" || exit 1
        CORS=true \
        MYSQL_HOST=localhost MYSQL_PORT="$MYSQL_PORT" MYSQL_DB=logger \
        MYSQL_RW_USER=user MYSQL_RO_USER=user-ro MYSQL_PASSWORD=pass \
        REDIS_HOST=localhost REDIS_PORT="$REDIS_PORT" REDIS_USER=default REDIS_TOKEN=opik \
        MINIO_HOST=localhost MINIO_HOST_VIEW=localhost \
        CASSANDRA_ENABLED=false MPM_ENABLED=false \
        FORCE_FAIL_ON_TIMEZONE_MISMATCH=False \
        MYSQL_MIN_MAX_ALLOWED_PACKET_MB=3 \
        PAYMENT_PUBLISHABLE_KEY=pk_test_stub \
        PAYMENT_SECRET_KEY=sk_test_stub \
        REACT_BIND_HOST=0.0.0.0 \
        REACT_BACKEND_HTTP_PORT="$EM_BACKEND_PORT" \
        REACT_BACKEND_HTTPS_PORT="$EM_BACKEND_ADMIN_PORT" \
        JWT_SAME_SITE=LAX \
        MPM_DRUID_ENABLED=False \
        SMART_API_KEY_ENABLED=False \
        COMET_REDIRECT_URL_SEGMENT=":$EM_PROXY_PORT" \
        OPIK_BASE_URL="http://localhost:${BACKEND_PORT}/" \
        COMET_LLM_INTEGRATION=true \
        JAVA_HOME="$em_jh" \
        nohup "$em_jh/bin/java" -jar "$EM_BACKEND_JAR" server "$EM_BACKEND_CONFIG_FILE" \
            > "$EM_BACKEND_LOG_FILE" 2>&1 &
        echo $! > "$EM_BACKEND_PID_FILE"
    )
    printf '%s\n' "$COMET_BACKEND_PATH" > "$EM_BACKEND_REPO_PATH_FILE"

    local pid
    pid=$(cat "$EM_BACKEND_PID_FILE")
    log_debug "EM backend process started with PID: $pid"

    sleep 3
    if ! kill -0 "$pid" 2>/dev/null; then
        log_warning "EM backend failed to start. Check logs: cat $EM_BACKEND_LOG_FILE"
        rm -f "$EM_BACKEND_PID_FILE"
        return 1
    fi
    log_success "EM backend process started (PID: $pid)"
    log_info "EM backend logs: tail -f $EM_BACKEND_LOG_FILE"
    if ! wait_for_em_backend_ready "$pid"; then
        log_warning "EM backend did not become ready in time; continuing"
        return 1
    fi
    return 0
}

stop_em_backend_local() {
    if [ ! -f "$EM_BACKEND_PID_FILE" ] && [ ! -f "$EM_BACKEND_REPO_PATH_FILE" ]; then
        return 0
    fi
    if [ -f "$EM_BACKEND_PID_FILE" ]; then
        local pid
        pid=$(cat "$EM_BACKEND_PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            log_info "Stopping EM backend (PID: $pid)..."
            local descendants
            descendants=$(get_descendants "$pid")
            kill -TERM "$pid" 2>/dev/null || true
            for p in $descendants; do kill -TERM "$p" 2>/dev/null || true; done
            for _ in {1..10}; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            if kill -0 "$pid" 2>/dev/null; then
                log_warning "Force killing EM backend..."
                kill -9 "$pid" 2>/dev/null || true
            fi
            for p in $descendants; do kill -9 "$p" 2>/dev/null || true; done
        else
            log_warning "EM backend PID file exists but process is not running (cleaning up stale PID file)"
        fi
    fi
    rm -f "$EM_BACKEND_PID_FILE" "$EM_BACKEND_REPO_PATH_FILE"
    log_success "EM backend stopped"
}

display_em_backend_process_status() {
    if [ -f "$EM_BACKEND_PID_FILE" ] && kill -0 "$(cat "$EM_BACKEND_PID_FILE")" 2>/dev/null; then
        echo -e "EM Backend:  ${GREEN}RUNNING${NC} (PID: $(cat "$EM_BACKEND_PID_FILE"))"
        return 0
    fi
    if em_backend_healthy; then
        echo -e "EM Backend:  ${GREEN}RUNNING${NC} (reused external instance on port ${EM_BACKEND_PORT})"
        return 0
    fi
    echo -e "EM Backend:  ${RED}STOPPED${NC}"
    return 1
}

build_em_frontend() {
    require_command npm
    log_info "Installing comet-react dependencies (npm install)..."
    if ( cd "$COMET_REACT_PATH" && npm install ); then
        log_success "comet-react dependencies installed"
    else
        log_error "comet-react npm install failed"
        return 1
    fi
}

# Point comet-react at the dev-runner's EM backend by writing public/config.js
# (gitignored; this is exactly what `npm run switch-env` rewrites). The existing
# file is backed up once so --stop can restore the developer's own config.
# NOTE: no /api suffix — the standalone backend serves Jersey at root ('/').
generate_em_frontend_config() {
    local cfg="$COMET_REACT_PATH/public/config.js"
    if [ -f "$cfg" ] && [ ! -f "$EM_FRONTEND_CONFIG_BAK_FILE" ]; then
        cp "$cfg" "$EM_FRONTEND_CONFIG_BAK_FILE"
        log_debug "Backed up existing comet-react config.js -> $EM_FRONTEND_CONFIG_BAK_FILE"
    fi
    mkdir -p "$COMET_REACT_PATH/public"
    cat > "$cfg" <<EOF
// Generated by opik dev-runner (EM stack). Restored from backup on --stop.
// URLs point at the single-origin EM proxy (:${EM_PROXY_PORT}) so the browser
// talks to one origin: /api -> comet-backend, /opik -> Opik. LLM_BASE_URL is
// how comet-react navigates to Opik (LLM_APPLICATION_URL falls back to /opik/).
var environmentVariablesOverwrite = {
  ENV: 'dev',
  PRODUCTION: false,
  NODE_ENV: 'production',
  BASE_URL: 'http://localhost:${EM_PROXY_PORT}/api/',
  ROOT_URL: 'http://localhost:${EM_PROXY_PORT}/',
  LLM_BASE_URL: 'http://localhost:${EM_PROXY_PORT}/opik/',
  SHOULD_LOAD_ANALYTICS: false,
  SENTRY_ENVIRONMENT: 'development',
  ON_PREMISE: true
};

try {
  global.environmentVariablesOverwrite = environmentVariablesOverwrite;
} catch (e) {
  /* This is for Mocha only, ignore in any other case */
}
EOF
    log_debug "Generated comet-react config.js -> $cfg (backend :${EM_BACKEND_PORT})"
}

start_em_frontend_local() {
    if ! em_frontend_enabled; then
        if em_stack_enabled; then
            log_warning "EM stack enabled but COMET_REACT_PATH not found; skipping comet-react frontend"
        fi
        return 0
    fi
    require_command npm

    if em_frontend_running; then
        log_warning "EM frontend is already running (PID: $(cat "$EM_FRONTEND_PID_FILE"))"
        return 0
    fi
    rm -f "$EM_FRONTEND_PID_FILE"

    if [ ! -d "$COMET_REACT_PATH/node_modules" ]; then
        log_warning "comet-react node_modules missing; installing..."
        build_em_frontend || { log_warning "Continuing without EM frontend"; return 1; }
    fi

    generate_em_frontend_config

    log_info "Starting comet-react dev server on port ${EM_FRONTEND_PORT}..."
    (
        cd "$COMET_REACT_PATH" || exit 1
        CI=true REACT_DEV_SERVER_PORT="$EM_FRONTEND_PORT" \
        ROOT_URL="http://localhost:${EM_PROXY_PORT}/" \
        BASE_URL="http://localhost:${EM_PROXY_PORT}/api/" \
        LLM_BASE_URL="http://localhost:${EM_PROXY_PORT}/opik/" \
        ON_PREMISE=true \
        nohup npm run start > "$EM_FRONTEND_LOG_FILE" 2>&1 &
        echo $! > "$EM_FRONTEND_PID_FILE"
    )
    printf '%s\n' "$COMET_REACT_PATH" > "$EM_FRONTEND_REPO_PATH_FILE"

    local pid
    pid=$(cat "$EM_FRONTEND_PID_FILE")
    log_debug "EM frontend process started with PID: $pid"

    sleep 3
    if ! kill -0 "$pid" 2>/dev/null; then
        log_warning "EM frontend failed to start. Check logs: cat $EM_FRONTEND_LOG_FILE"
        rm -f "$EM_FRONTEND_PID_FILE"
        return 1
    fi
    log_success "EM frontend process started (PID: $pid)"
    log_info "EM frontend available at: ${GREEN}http://localhost:${EM_FRONTEND_PORT}${NC} (webpack dev server may take a bit to compile)"
    log_info "EM frontend logs: tail -f $EM_FRONTEND_LOG_FILE"
    return 0
}

stop_em_frontend_local() {
    if [ ! -f "$EM_FRONTEND_PID_FILE" ] && [ ! -f "$EM_FRONTEND_REPO_PATH_FILE" ]; then
        return 0
    fi
    if [ -f "$EM_FRONTEND_PID_FILE" ]; then
        local pid
        pid=$(cat "$EM_FRONTEND_PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            log_info "Stopping EM frontend (PID: $pid)..."
            local descendants
            descendants=$(get_descendants "$pid")
            kill -TERM "$pid" 2>/dev/null || true
            for p in $descendants; do kill -TERM "$p" 2>/dev/null || true; done
            for _ in {1..10}; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            if kill -0 "$pid" 2>/dev/null; then
                log_warning "Force killing EM frontend..."
                kill -9 "$pid" 2>/dev/null || true
            fi
            for p in $descendants; do kill -9 "$p" 2>/dev/null || true; done
        else
            log_warning "EM frontend PID file exists but process is not running (cleaning up stale PID file)"
        fi
    fi

    # Restore the developer's original comet-react config.js if we backed it up.
    local cr_repo="${COMET_REACT_PATH:-}"
    if [ -z "$cr_repo" ] && [ -f "$EM_FRONTEND_REPO_PATH_FILE" ]; then
        cr_repo=$(cat "$EM_FRONTEND_REPO_PATH_FILE")
    fi
    if [ -n "$cr_repo" ] && [ -f "$EM_FRONTEND_CONFIG_BAK_FILE" ]; then
        cp "$EM_FRONTEND_CONFIG_BAK_FILE" "$cr_repo/public/config.js" && rm -f "$EM_FRONTEND_CONFIG_BAK_FILE"
        log_info "Restored comet-react public/config.js from backup"
    fi

    rm -f "$EM_FRONTEND_PID_FILE" "$EM_FRONTEND_REPO_PATH_FILE"
    log_success "EM frontend stopped"
}

display_em_frontend_process_status() {
    if [ -f "$EM_FRONTEND_PID_FILE" ] && kill -0 "$(cat "$EM_FRONTEND_PID_FILE")" 2>/dev/null; then
        echo -e "EM Frontend: ${GREEN}RUNNING${NC} (PID: $(cat "$EM_FRONTEND_PID_FILE"))"
        return 0
    fi
    echo -e "EM Frontend: ${RED}STOPPED${NC}"
    return 1
}

# Render the single-origin nginx config that fronts EM + Opik. Routes mirror the
# production frontend-nginx (comet-ml-helm-chart), adapted to the local dev
# servers reached via host.docker.internal. Longest-prefix wins, so /opik/api
# and /api are matched before /opik and /. Trailing-slash proxy_pass strips the
# location prefix (so /api/x -> comet-backend /x, /opik/api/x -> opik-backend /x);
# /opik/ is passed through unchanged (Opik Vite serves under base /opik).
generate_em_proxy_conf() {
    cat > "$EM_PROXY_CONF" <<EOF
worker_processes 1;
events { worker_connections 4096; }
http {
    # Non-ws requests get an EMPTY Connection header so upstream keepalive kicks
    # in (nginx omits an empty header). Without this the Opik Vite dev server's
    # hundreds-of-ES-modules waterfall re-opens a TCP connection per request and
    # takes >1min to load through the proxy. ws requests get Connection: upgrade.
    map \$http_upgrade \$connection_upgrade { default upgrade; '' ''; }

    # Keepalive connection pools to each dev server / backend.
    upstream em_opik_be { server host.docker.internal:${BACKEND_PORT}; keepalive 64; }
    upstream em_opik_fe { server host.docker.internal:${FRONTEND_PORT}; keepalive 128; }
    upstream em_comet_be { server host.docker.internal:${EM_BACKEND_PORT}; keepalive 32; }
    upstream em_comet_fe { server host.docker.internal:${EM_FRONTEND_PORT}; keepalive 128; }

    server {
        listen 80;
        client_max_body_size 100g;
        proxy_http_version 1.1;
        proxy_set_header Host \$http_host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;

        # Opik backend API (strip /opik/api -> /)
        location /opik/api/ { proxy_pass http://em_opik_be/; }
        # Opik UI — Vite dev server (comet mode, base /opik) + HMR websocket
        location /opik/     { proxy_pass http://em_opik_fe; }
        # EM (comet-backend) API (strip /api -> /)
        location /api/      { proxy_pass http://em_comet_be/; }
        # EM UI — comet-react webpack dev server + HMR websocket
        location /          { proxy_pass http://em_comet_fe; }
    }
}
EOF
    log_debug "Generated EM proxy nginx config: $EM_PROXY_CONF"
}

em_proxy_running() {
    command -v docker >/dev/null 2>&1 || return 1
    [ -n "$(docker ps -q -f "name=^${EM_PROXY_CONTAINER}$" 2>/dev/null)" ]
}

start_em_proxy() {
    em_stack_enabled || return 0
    require_command docker
    generate_em_proxy_conf
    # Recreate cleanly so config/port changes always take effect.
    docker rm -f "$EM_PROXY_CONTAINER" >/dev/null 2>&1 || true
    log_info "Starting EM single-origin proxy (nginx) on port ${EM_PROXY_PORT}..."
    if docker run -d --name "$EM_PROXY_CONTAINER" \
        --add-host=host.docker.internal:host-gateway \
        -p "${EM_PROXY_PORT}:80" \
        -v "${EM_PROXY_CONF}:/etc/nginx/nginx.conf:ro" \
        nginx:alpine >/dev/null 2>&1; then
        log_success "EM proxy started"
        log_info "Integrated UI (EM + Opik): ${GREEN}http://localhost:${EM_PROXY_PORT}${NC}"
        log_info "  Opik under: ${GREEN}http://localhost:${EM_PROXY_PORT}/opik${NC}"
    else
        log_warning "EM proxy failed to start. Check: docker logs ${EM_PROXY_CONTAINER}"
        return 1
    fi
}

stop_em_proxy() {
    # Cheap gate: the conf only exists once the proxy has been started, so when
    # the EM stack was never used this returns before any docker shell-out —
    # keeping --stop/--restart zero-overhead for plain Opik dev.
    [ -f "$EM_PROXY_CONF" ] || return 0
    command -v docker >/dev/null 2>&1 || return 0
    if [ -n "$(docker ps -aq -f "name=^${EM_PROXY_CONTAINER}$" 2>/dev/null)" ]; then
        log_info "Stopping EM proxy..."
        docker rm -f "$EM_PROXY_CONTAINER" >/dev/null 2>&1 || true
        log_success "EM proxy stopped"
    fi
    rm -f "$EM_PROXY_CONF"
}

display_em_proxy_process_status() {
    if em_proxy_running; then
        echo -e "EM Proxy:    ${GREEN}RUNNING${NC} (http://localhost:${EM_PROXY_PORT})"
        return 0
    fi
    echo -e "EM Proxy:    ${RED}STOPPED${NC}"
    return 1
}

# Bring the EM pair up / down as a unit. Safe to call unconditionally: the
# start wrapper is gated on em_stack_enabled, and the stop functions quietly
# no-op when there's nothing tracked.
start_em_stack() {
    em_stack_enabled || return 0
    log_info "Starting Comet EM stack (comet-backend + comet-react + proxy)..."
    start_em_backend_local || log_warning "EM backend startup failed; continuing"
    start_em_frontend_local || log_warning "EM frontend startup failed; continuing"
    # Proxy last: it fronts opik FE (comet mode) + comet-react + both backends
    # on one origin, which is what comet mode's relative URLs require.
    start_em_proxy || log_warning "EM proxy startup failed; open services on individual ports"
}

stop_em_stack() {
    stop_em_proxy
    stop_em_frontend_local
    stop_em_backend_local
}

# ---- Thin hooks called from dev-runner.sh (keep core-script changes minimal) ----

# Extra `npm run start` args to put Opik FE in comet mode (empty unless enabled).
em_opik_vite_args() {
    if em_stack_enabled; then printf -- '-- --mode comet'; fi
}

# Prep the Opik FE env for comet mode: unset VITE_FE_PLUGINS so ACTIVE_PLUGINS
# falls back to [MODE]=["comet"]. No-op unless the platform stack is enabled.
em_prepare_opik_comet_env() {
    em_stack_enabled || return 0
    unset VITE_FE_PLUGINS
    log_info "Starting frontend in comet mode (platform-connected, base /opik)"
}

# Prep the Opik BACKEND env for platform auth (M2). With AUTH_ENABLED=true the
# Opik backend authenticates each request and resolves the Comet workspace by
# calling the "React service" (= comet-backend) at REACT_SERVICE_URL — it POSTs
# the browser's `sessionToken` cookie to /opik/auth-session and looks up
# /workspaces/workspace-id. Without this the Opik backend only knows its own
# `default` workspace and 404s every call scoped to a Comet workspace.
# No-op unless the platform stack is enabled.
em_prepare_opik_backend_auth_env() {
    em_stack_enabled || return 0
    export AUTH_ENABLED=true
    export REACT_SERVICE_URL="http://localhost:${EM_BACKEND_PORT}"
    # Skip the onboarding "Almost ready…" demo-loading screen. It polls forever
    # for the "Opik Demo Agent Observability" project, which only gets created by
    # comet-backend's post-signup hook (OPIK_DEMO_PROJECT_CREATION + a reachable
    # opik-python-backend URL) — not wired in local dev. This toggle
    # (config.yml demoDataEnabled) is surfaced via /v1/private/toggles; false ->
    # the FE proceeds straight to the empty workspace instead of hanging.
    export TOGGLE_DEMO_DATA_ENABLED=false
    log_info "Opik backend: platform auth ON (REACT_SERVICE_URL=$REACT_SERVICE_URL), demo-data screen OFF"
}

# Rebuild the EM backend jar on --restart so comet-backend changes are picked up.
em_restart_build() {
    em_stack_enabled || return 0
    log_info "Building Comet EM stack (comet-backend)..."
    build_em_backend || log_warning "EM backend build failed; will try existing jar on start"
}

# EM lines for verify_services status output.
em_print_status() {
    if em_stack_enabled || [ -f "$EM_BACKEND_PID_FILE" ]; then
        display_em_backend_process_status || true
    fi
    if em_frontend_enabled || [ -f "$EM_FRONTEND_PID_FILE" ]; then
        display_em_frontend_process_status || true
    fi
    if em_stack_enabled; then
        display_em_proxy_process_status || true
        echo -e "  ${BLUE}Integrated EM + Opik UI: http://localhost:${EM_PROXY_PORT}  (Opik: /opik)${NC}"
    fi
}

# EM lines for the verify_services / Logs section.
em_print_logs() {
    if em_stack_enabled || [ -f "$EM_BACKEND_LOG_FILE" ]; then
        echo "  EM Backend:       tail -f $EM_BACKEND_LOG_FILE"
    fi
    if em_frontend_enabled || [ -f "$EM_FRONTEND_LOG_FILE" ]; then
        echo "  EM Frontend:      tail -f $EM_FRONTEND_LOG_FILE"
    fi
    if em_stack_enabled; then
        echo "  EM Proxy:         docker logs -f ${EM_PROXY_CONTAINER}"
    fi
}

# EM env-var docs for show_usage.
em_print_usage() {
    echo "  PLATFORM_ENABLED=true    - Opik-team only: also run the Comet EM/Platform stack"
    echo "                          (comet-backend ReactWebappServerApplication + comet-react)"
    echo "                          alongside Opik behind a single-origin proxy, reusing Opik's"
    echo "                          dev MySQL/Redis/MinIO — no comet-helm-mini needed. Runs with"
    echo "                          Cassandra disabled. Heavy Maven reactor build, so off by"
    echo "                          default. comet-backend/comet-react auto-detected as siblings."
    echo "  COMET_BACKEND_PATH=<p>   - Override comet-backend checkout (default: <opik-root>/../comet-backend)"
    echo "  COMET_REACT_PATH=<p>     - Override comet-react checkout (default: <opik-root>/../comet-react)"
    echo "  EM_JAVA_HOME=<p>      - JDK for the EM backend. comet-backend needs JDK 17/21 (Lombok"
    echo "                          1.18.30) while opik needs JDK 25, so they can't share JAVA_HOME."
    echo "                          If unset, dev-runner auto-detects an installed 17 (then 21) via"
    echo "                          java_home / SDKMAN / Linux / Homebrew, independent of your"
    echo "                          default JDK. EM build + run use this JDK; Opik is unaffected."
    echo "  EM_JAVA_ACCEPTED_MAJORS=\"17 21\" - Override the accepted EM JDK majors / preference order"
    echo "  EM_BACKEND_PORT=<n>   - EM backend (ReactWebapp) port (default: 8200 + worktree offset)"
    echo "  EM_FRONTEND_PORT=<n>  - EM frontend (comet-react) port (default: 8300 + worktree offset)"
    echo "  EM_PROXY_PORT=<n>     - Single-origin proxy port = the integrated EM+Opik UI URL you"
    echo "                          open (default: 9100 + worktree offset). Opik lives at /opik."
}

# --platform-build entrypoint.
em_build() {
    if ! em_stack_enabled; then
        log_error "EM stack not enabled. Set PLATFORM_ENABLED=true (and ensure COMET_BACKEND_PATH resolves)."
        return 1
    fi
    build_em_backend || return 1
    if em_frontend_enabled; then
        build_em_frontend || return 1
    fi
}

# "port:label" lines for check_port_collisions (empty unless platform enabled).
# EM backend/proxy are skipped when already healthy/running (reuse is expected).
em_collision_ports() {
    em_stack_enabled || return 0
    if ! em_backend_healthy; then
        echo "$EM_BACKEND_PORT:EM Backend"
        echo "$EM_BACKEND_ADMIN_PORT:EM Backend Admin"
    fi
    if em_frontend_enabled; then
        echo "$EM_FRONTEND_PORT:EM Frontend"
    fi
    if ! em_proxy_running; then
        echo "$EM_PROXY_PORT:EM Proxy"
    fi
}
