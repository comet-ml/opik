import dataclasses
import json
import logging
import os
import tempfile
from typing import Any, Dict, List, Optional

import filelock

from . import constants

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class Param:
    name: str
    type: str = "str"


@dataclasses.dataclass
class AgentInfo:
    name: str
    executable: str
    source_file: str
    description: str = ""
    language: str = "python"
    params: List[Param] = dataclasses.field(default_factory=list)
    timeout: Optional[int] = None
    project: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "name": self.name,
            "executable": self.executable,
            "source_file": self.source_file,
            "description": self.description,
            "language": self.language,
            "params": [dataclasses.asdict(p) for p in self.params],
        }
        if self.timeout is not None:
            d["timeout"] = self.timeout
        if self.project is not None:
            d["project"] = self.project
        return d

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "AgentInfo":
        params = [Param(**p) for p in data.get("params", [])]
        return cls(
            name=data["name"],
            executable=data["executable"],
            source_file=data["source_file"],
            description=data.get("description", ""),
            language=data.get("language", "python"),
            params=params,
            timeout=data.get("timeout"),
            project=data.get("project"),
        )


def register_agent(info: AgentInfo) -> None:
    """Upsert an agent entry in the local registry (file-locked for concurrent writers)."""
    constants.ensure_opik_home()
    agents_path = constants.agents_file()
    lock_path = agents_path + ".lock"

    with filelock.FileLock(lock_path):
        agents = _load_agents_list()
        agents = [a for a in agents if a.name != info.name]
        agents.append(info)
        _write_agents_list(agents)


def load_agents() -> Dict[str, AgentInfo]:
    """Load all registered agents as a name-keyed dict for dispatch lookup."""
    agents = _load_agents_list()
    return {a.name: a for a in agents}


def _load_agents_list() -> List[AgentInfo]:
    agents_path = constants.agents_file()
    if not os.path.exists(agents_path):
        return []

    try:
        with open(agents_path, "r") as f:
            data = json.load(f)
        return [AgentInfo.from_dict(a) for a in data.get("agents", [])]
    except (json.JSONDecodeError, KeyError, OSError):
        LOGGER.warning("Corrupted agents.json, resetting")
        return []


def _write_agents_list(agents: List[AgentInfo]) -> None:
    data = {"agents": [a.to_dict() for a in agents]}
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
