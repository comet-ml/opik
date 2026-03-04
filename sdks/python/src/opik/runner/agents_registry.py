import json
import logging
import os
import tempfile
from typing import Any, Dict, List

import filelock

from . import constants

LOGGER = logging.getLogger(__name__)


def register_agent(info: Dict[str, Any]) -> None:
    """Upsert an agent entry in the local registry (file-locked for concurrent writers)."""
    constants.ensure_opik_home()
    agents_path = constants.agents_file()
    lock_path = agents_path + ".lock"

    with filelock.FileLock(lock_path):
        agents = _load_agents_list()
        agents = [a for a in agents if a["name"] != info["name"]]
        agents.append(info)
        _write_agents_list(agents)


def load_agents() -> Dict[str, Dict[str, Any]]:
    """Load all registered agents as a name-keyed dict for dispatch lookup."""
    agents = _load_agents_list()
    return {a["name"]: a for a in agents}


def _load_agents_list() -> List[Dict[str, Any]]:
    agents_path = constants.agents_file()
    if not os.path.exists(agents_path):
        return []

    try:
        with open(agents_path, "r") as f:
            data = json.load(f)
        return data.get("agents", [])
    except (json.JSONDecodeError, KeyError, OSError):
        LOGGER.warning("Corrupted agents.json, resetting")
        return []


def _write_agents_list(agents: List[Dict[str, Any]]) -> None:
    data = {"agents": agents}
    agents_path = constants.agents_file()
    dir_path = os.path.dirname(agents_path)

    fd, tmp_path = tempfile.mkstemp(dir=dir_path, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=2)
        os.replace(tmp_path, agents_path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise
