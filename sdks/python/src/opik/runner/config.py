"""Runner configuration management (~/.opik/)."""

import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

LOGGER = logging.getLogger(__name__)

RUNNER_CONFIG_DIR = Path.home() / ".opik"
RUNNER_CONFIG_FILE = RUNNER_CONFIG_DIR / "runner.json"
AGENTS_FILE = RUNNER_CONFIG_DIR / "agents.json"


# --- Runner config (pairing state) ---

def load_runner_config() -> Dict[str, Any]:
    if not RUNNER_CONFIG_FILE.exists():
        return {}
    try:
        return json.loads(RUNNER_CONFIG_FILE.read_text())
    except (json.JSONDecodeError, OSError):
        LOGGER.warning("Failed to read runner config at %s", RUNNER_CONFIG_FILE)
        return {}


def save_runner_config(config: Dict[str, Any]) -> None:
    RUNNER_CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    RUNNER_CONFIG_FILE.write_text(json.dumps(config, indent=2))


def get_runner_id() -> Optional[str]:
    return load_runner_config().get("runner_id")


def get_redis_url() -> Optional[str]:
    return load_runner_config().get("redis_url")


def clear_runner_config() -> None:
    if RUNNER_CONFIG_FILE.exists():
        RUNNER_CONFIG_FILE.unlink()


# --- Agents registry (self-registered by @entrypoint) ---

def load_agents() -> Dict[str, Dict[str, str]]:
    """Load registered agents. Returns dict keyed by agent name."""
    if not AGENTS_FILE.exists():
        return {}
    try:
        return json.loads(AGENTS_FILE.read_text())
    except (json.JSONDecodeError, OSError):
        LOGGER.warning("Failed to read agents file at %s", AGENTS_FILE)
        return {}


def save_agents(agents: Dict[str, Dict[str, str]]) -> None:
    RUNNER_CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    AGENTS_FILE.write_text(json.dumps(agents, indent=2))


def register_agent(name: str, python: str, file: str, project: str, params: Optional[List[Dict[str, str]]] = None) -> None:
    """Register or update an agent entry."""
    agents = load_agents()
    agents[name] = {
        "python": python,
        "file": file,
        "project": project,
        "params": params or [],
    }
    save_agents(agents)
    LOGGER.debug("Registered agent '%s' (python=%s, file=%s, project=%s)", name, python, file, project)


def unregister_agent(name: str) -> None:
    agents = load_agents()
    if name in agents:
        del agents[name]
        save_agents(agents)
