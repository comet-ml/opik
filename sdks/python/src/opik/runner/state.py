import dataclasses
import json
import logging
import os
import signal
import sys
from typing import Optional

from . import constants

LOGGER = logging.getLogger(__name__)


def send_shutdown_signal(pid: int) -> None:
    if sys.platform == "win32":
        os.kill(pid, signal.CTRL_C_EVENT)
    else:
        os.kill(pid, signal.SIGTERM)


@dataclasses.dataclass
class RunnerState:
    runner_id: str
    pid: int
    name: str
    base_url: str


def save_runner_state(state: RunnerState) -> None:
    constants.ensure_opik_home()
    state_path = constants.runner_state_file()
    with open(state_path, "w") as f:
        json.dump(dataclasses.asdict(state), f, indent=2)


def load_runner_state() -> Optional[RunnerState]:
    state_path = constants.runner_state_file()
    if not os.path.exists(state_path):
        return None
    try:
        with open(state_path, "r") as f:
            data = json.load(f)
        return RunnerState(**data)
    except (json.JSONDecodeError, KeyError, TypeError):
        return None


def clear_runner_state() -> None:
    try:
        os.remove(constants.runner_state_file())
    except FileNotFoundError:
        pass
