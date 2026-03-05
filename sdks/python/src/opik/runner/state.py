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

    def save(self) -> None:
        constants.ensure_opik_home()
        state_path = constants.runner_state_file()
        with open(state_path, "w") as f:
            json.dump(dataclasses.asdict(self), f, indent=2)

    @classmethod
    def load(cls) -> Optional["RunnerState"]:
        state_path = constants.runner_state_file()
        if not os.path.exists(state_path):
            return None
        try:
            with open(state_path, "r") as f:
                data = json.load(f)
            return cls(**data)
        except (json.JSONDecodeError, KeyError, TypeError):
            return None

    @staticmethod
    def clear() -> None:
        try:
            os.remove(constants.runner_state_file())
        except FileNotFoundError:
            pass
