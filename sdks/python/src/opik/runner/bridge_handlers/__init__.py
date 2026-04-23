"""Bridge command handler base, error types, file mutation queue, and stub handler."""

import abc
import os
import threading
from pathlib import Path
from typing import Any, Dict


class CommandError(Exception):
    def __init__(self, code: str, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(f"{code}: {message}")


class BaseHandler(abc.ABC):
    @abc.abstractmethod
    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]: ...


class FileLockRegistry:
    """Per-file lock keyed by realpath. Serializes writes to the same file."""

    def __init__(self) -> None:
        self._locks: Dict[str, threading.Lock] = {}
        self._meta_lock = threading.Lock()

    def lock(self, path: Path) -> threading.Lock:
        real = os.path.realpath(path)
        with self._meta_lock:
            if real not in self._locks:
                self._locks[real] = threading.Lock()
            return self._locks[real]


class StubHandler(BaseHandler):
    def execute(self, args: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        raise CommandError("not_implemented", "Command type not yet implemented")


WRITE_COMMANDS = {"WriteFile", "EditFile", "Exec"}
