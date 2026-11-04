"""Opt-in hardening of the in-container Docker daemon used by the docker executor strategy.

entrypoint.sh runs this file as a script before starting dockerd, so it imports only the standard library.
"""
import json
import os
import sys

HARDENING_ENV_VAR = "PYTHON_CODE_EXECUTOR_DOCKER_HARDENING"
ALLOW_NETWORK_ENV_VAR = "PYTHON_CODE_EXECUTOR_ALLOW_NETWORK"
OPERATOR_CONFIG_PATH = "/etc/docker/daemon.json"
# Outside /etc/docker, which an operator may mount read-only, and not under /tmp, which the dind
# startup script replaces with a fresh tmpfs before dockerd reads its config.
HARDENED_CONFIG_PATH = "/run/opik-dockerd/daemon.json"


def hardening_enabled() -> bool:
    """Any value other than true/false (any case) or empty raises, so a typo fails startup instead of
    leaving the executor unhardened."""
    value = os.getenv(HARDENING_ENV_VAR, "").lower()
    if value in ("", "false"):
        return False
    if value == "true":
        return True
    raise ValueError(f"{HARDENING_ENV_VAR} must be true or false, not {value[:32]!r}")


def hardened_daemon_config(config: dict, allow_network: bool) -> dict:
    # Root in an executor container maps to an unprivileged host uid range.
    hardened = {**config, "userns-remap": "default"}
    if not allow_network:
        # Executor containers without network access need no bridge or firewall rules in the daemon.
        hardened.update({"bridge": "none", "iptables": False, "ip6tables": False})
    return hardened


def write_hardened_config(operator_config_path: str, hardened_config_path: str) -> None:
    config = {}
    if os.path.exists(operator_config_path):
        with open(operator_config_path) as f:
            config = json.load(f)
    allow_network = os.getenv(ALLOW_NETWORK_ENV_VAR, "false").lower() == "true"
    os.makedirs(os.path.dirname(hardened_config_path), exist_ok=True)
    with open(hardened_config_path, "w") as f:
        json.dump(hardened_daemon_config(config, allow_network), f)


def main() -> None:
    """Prints the daemon config file to pass to dockerd, or nothing when hardening is off."""
    try:
        if not hardening_enabled():
            return
        write_hardened_config(OPERATOR_CONFIG_PATH, HARDENED_CONFIG_PATH)
    except (ValueError, OSError) as e:
        sys.exit(f"Docker daemon hardening failed: {e}")
    print(f"Docker daemon hardening enabled, config: {HARDENED_CONFIG_PATH}", file=sys.stderr)
    print(HARDENED_CONFIG_PATH)


if __name__ == "__main__":
    main()
