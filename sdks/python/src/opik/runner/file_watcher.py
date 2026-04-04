"""File watcher — monitors repo for code changes and triggers child restart."""

import logging
import threading
from pathlib import Path
from typing import Callable, Set

import watchfiles

LOGGER = logging.getLogger(__name__)

DEFAULT_EXTENSIONS = {".py", ".js", ".ts", ".yaml", ".yml", ".json", ".toml"}
DEFAULT_IGNORE = {"__pycache__", ".venv", "node_modules", ".git"}


class FileWatcher:
    def __init__(
        self,
        repo_root: Path,
        on_change: Callable[[Set[Path]], None],
        extensions: Set[str] = DEFAULT_EXTENSIONS,
        debounce_seconds: float = 1.0,
    ) -> None:
        self._repo_root = repo_root
        self._on_change = on_change
        self._extensions = extensions
        self._debounce_seconds = debounce_seconds

    def run(self, shutdown_event: threading.Event) -> None:
        def _should_watch(change: watchfiles.Change, path: str) -> bool:
            p = Path(path)
            if p.suffix not in self._extensions:
                return False
            for part in p.parts:
                if part in DEFAULT_IGNORE:
                    return False
            return True

        LOGGER.info("Watching %s for changes", self._repo_root)

        for changes in watchfiles.watch(
            self._repo_root,
            watch_filter=_should_watch,
            debounce=int(self._debounce_seconds * 1000),
            stop_event=shutdown_event,
            rust_timeout=5000,
        ):
            if shutdown_event.is_set():
                break
            paths = {Path(path) for _, path in changes}
            LOGGER.info("File changes detected: %s", [p.name for p in paths])
            try:
                self._on_change(paths)
            except Exception:
                LOGGER.error("Error in on_change callback", exc_info=True)
