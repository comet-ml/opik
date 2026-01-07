#!/usr/bin/env bash
set -euo pipefail

# Install ESNODE Agent from an extracted tarball or build tree.
# - Copies binary to /usr/local/bin
# - Creates systemd service esnode-core.service

BIN_SRC="${1:-./esnode-core}"
BIN_DST="/usr/local/bin/esnode-core"
SERVICE_PATH="/etc/systemd/system/esnode-core.service"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -x "${BIN_SRC}" ]]; then
  echo "ERROR: ${BIN_SRC} not found or not executable" >&2
  exit 1
fi

echo "==> Installing binary to ${BIN_DST}"
sudo install -m 0755 "${BIN_SRC}" "${BIN_DST}"

echo "==> Ensuring config and TSDB paths exist"
sudo mkdir -p /etc/esnode
sudo mkdir -p /var/lib/esnode/tsdb
sudo chmod 755 /var/lib/esnode
sudo chmod 755 /var/lib/esnode/tsdb

echo "==> Writing systemd unit ${SERVICE_PATH}"
if [[ -f "${SCRIPT_DIR}/../../deploy/systemd/esnode-core.service" ]]; then
  # Use packaged service if present
  sudo install -m 0644 "${SCRIPT_DIR}/../../deploy/systemd/esnode-core.service" "${SERVICE_PATH}"
else
  sudo tee "${SERVICE_PATH}" >/dev/null <<'EOF'
[Unit]
Description=ESNODE Agent (host metrics exporter)
After=network.target

[Service]
ExecStart=/usr/local/bin/esnode-core daemon
Restart=on-failure
RestartSec=5
User=root
AmbientCapabilities=CAP_NET_ADMIN

[Install]
WantedBy=multi-user.target
EOF
fi

echo "==> Reloading systemd and enabling service"
sudo systemctl daemon-reload
sudo systemctl enable --now esnode-core.service

echo "ESNODE-Core installed. Verify with: systemctl status esnode-core.service"
