# ESNODE-Core smoke test: build, run agent locally, and curl endpoints.
# Requirements: bash, curl, jq (optional for pretty JSON), cargo.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_BIN="$ROOT_DIR/target/release/esnode-core"

cleanup() {
  [[ -n "${AGENT_PID:-}" ]] && kill "$AGENT_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> Building workspace (release)..."
cargo build --workspace --release

echo "==> Starting esnode-core on :9100..."
"$AGENT_BIN" > /tmp/esnode-core.log 2>&1 &
AGENT_PID=$!
sleep 2

echo "==> Hitting agent endpoints..."
curl -sf http://localhost:9100/healthz | cat
echo
curl -sf http://localhost:9100/status | head -c 400 && echo
curl -sf http://localhost:9100/metrics | head -n 20
echo
echo "==> Smoke test complete. Logs: /tmp/esnode-core.log"
