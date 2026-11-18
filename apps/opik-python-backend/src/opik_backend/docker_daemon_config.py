"""Opt-in hardening of the in-container Docker daemon used by the docker executor strategy.

entrypoint.sh runs this file as a script before starting dockerd, so it imports only the standard library.
"""
import json
import os
import re
import sys

HARDENING_ENV_VAR = "PYTHON_CODE_EXECUTOR_DOCKER_HARDENING"
ALLOW_NETWORK_ENV_VAR = "PYTHON_CODE_EXECUTOR_ALLOW_NETWORK"
OPERATOR_CONFIG_PATH = "/etc/docker/daemon.json"
# Outside /etc/docker, which an operator may mount read-only, and not under /tmp, which the dind
# startup script replaces with a fresh tmpfs before dockerd reads its config.
HARDENED_CONFIG_PATH = "/run/opik-dockerd/daemon.json"
MAX_USER_NAMESPACES_PATH = "/proc/sys/user/max_user_namespaces"


def hardening_enabled() -> bool:
    """Any value other than true/false (any case) or empty raises, so a typo fails startup instead of
    leaving the executor unhardened."""
    value = os.getenv(HARDENING_ENV_VAR, "").lower()
    if value in ("", "false"):
        return False
    if value == "true":
        return True
    raise ValueError(f"{HARDENING_ENV_VAR} must be true or false, not {value[:32]!r}")


def check_user_namespaces_allowed(max_user_namespaces_path: str) -> None:
    """userns-remap needs the host to allow user namespaces. With the limit at 0, user namespace creation is disabled:
    dockerd still starts but every executor container fails to, so the backend would look healthy while rejecting
    every evaluation. An unreadable limit or one that isn't a non-negative integer fails too."""
    with open(max_user_namespaces_path) as f:
        limit = f.read().strip()
    if not re.fullmatch(r"[0-9]+", limit, flags=re.ASCII):
        raise ValueError(f"{max_user_namespaces_path} must hold a non-negative integer, not {limit[:32]!r}")
    if int(limit) == 0:
        raise ValueError(
            f"user namespaces are disabled on this host ({max_user_namespaces_path} is 0), so executor containers "
            f"can't start with {HARDENING_ENV_VAR}=true; allow user namespaces on the node (user.max_user_namespaces)"
        )


def hardened_daemon_config(config: dict, allow_network: bool) -> dict:
    # Root in an executor container maps to an unprivileged host uid range.
    hardened = {**config, "userns-remap": "default"}
    if not allow_network:
        # Executor containers without network access need no bridge or firewall rules in the daemon.
        hardened.update({"bridge": "none", "iptables": False, "ip6tables": False})
    return hardened


def write_hardened_config(operator_config_path: str, hardened_config_path: str) -> None:
    check_user_namespaces_allowed(MAX_USER_NAMESPACES_PATH)
    config = {}
    if os.path.exists(operator_config_path):
        with open(operator_config_path) as f:
            config = json.load(f)
        if not isinstance(config, dict):
            raise ValueError(f"{operator_config_path} must hold a JSON object, not {type(config).__name__}")
    allow_network = os.getenv(ALLOW_NETWORK_ENV_VAR, "false").lower() == "true"
    os.makedirs(os.path.dirname(hardened_config_path), exist_ok=True)
    with open(hardened_config_path, "w") as f:
        json.dump(hardened_daemon_config(config, allow_network), f)


def main() -> None:
    """With hardening on, writes the merged config to HARDENED_CONFIG_PATH and prints that path on stdout for
    entrypoint.sh to pass to dockerd as --config-file. With hardening off, prints nothing. Exits non-zero on a bad
    setting or an unreadable or unwritable config."""
    try:
        if not hardening_enabled():
            return
        write_hardened_config(OPERATOR_CONFIG_PATH, HARDENED_CONFIG_PATH)
    except (ValueError, OSError) as e:
        sys.exit(f"Docker daemon hardening failed ({OPERATOR_CONFIG_PATH} -> {HARDENED_CONFIG_PATH}): {e}")
    print(f"Docker daemon hardening enabled, config: {HARDENED_CONFIG_PATH}", file=sys.stderr)
    print(HARDENED_CONFIG_PATH)


if __name__ == "__main__":
    main()
