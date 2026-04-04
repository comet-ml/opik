"""Supervisor — manages child process lifecycle, bridge polling, file watching, and heartbeat."""

import logging
import shlex
import signal
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from ..rest_api.core.api_error import ApiError
from .bridge_handlers import StubHandler
from .bridge_loop import BridgePollLoop
from .file_watcher import FileWatcher
from .stability_guard import StabilityGuard

LOGGER = logging.getLogger(__name__)

_HEARTBEAT_INTERVAL = 5.0
_GRACEFUL_TIMEOUT = 10
_RESTART_DEBOUNCE = 1.0


class Supervisor:
    def __init__(
        self,
        command: List[str],
        env: Dict[str, str],
        repo_root: Path,
        runner_id: str,
        api: Any,
    ) -> None:
        self._command = command
        self._env = env
        self._repo_root = repo_root
        self._runner_id = runner_id
        self._api = api
        self._shutdown_event = threading.Event()
        self._child: Optional[subprocess.Popen] = None
        self._child_lock = threading.Lock()
        self._deliberate_restart = False
        self._guard = StabilityGuard()
        self._last_restart_time = 0.0

    def run(self) -> None:
        self._install_signal_handlers()

        heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop, name="supervisor-heartbeat", daemon=True
        )
        heartbeat_thread.start()

        handlers: Dict[str, Any] = {
            "read_file": StubHandler(),
            "write_file": StubHandler(),
            "edit_file": StubHandler(),
            "list_files": StubHandler(),
            "search_files": StubHandler(),
        }
        bridge_loop = BridgePollLoop(
            self._api, self._runner_id, handlers, self._shutdown_event
        )
        bridge_thread = threading.Thread(
            target=bridge_loop.run, name="bridge-poll", daemon=True
        )
        bridge_thread.start()

        watcher = FileWatcher(self._repo_root, self._on_file_change)
        watcher_thread = threading.Thread(
            target=watcher.run, args=(self._shutdown_event,), name="file-watcher", daemon=True
        )
        watcher_thread.start()

        with self._child_lock:
            self._child = self._start_child()

        try:
            self._main_loop()
        finally:
            self._stop_child()
            LOGGER.info("Supervisor shutdown complete")

    def _main_loop(self) -> None:
        while not self._shutdown_event.is_set():
            with self._child_lock:
                child = self._child
            if child is None:
                self._shutdown_event.wait(0.5)
                continue

            try:
                exit_code = child.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                continue

            with self._child_lock:
                if self._deliberate_restart:
                    self._deliberate_restart = False
                    continue

                self._child = None

            if self._shutdown_event.is_set():
                break

            if exit_code == 0:
                LOGGER.info("Child exited cleanly (code 0)")
                self._shutdown_event.set()
                break

            LOGGER.warning("Child exited with code %d", exit_code)
            self._guard.record_crash()

            if not self._guard.is_stable():
                LOGGER.error("Child crashed too many times in window, stopping restarts")
                self._shutdown_event.set()
                break

            with self._child_lock:
                self._child = self._start_child()

    def _start_child(self) -> subprocess.Popen:
        env = {**self._env, "OPIK_SUPERVISED": "true"}
        LOGGER.info("Starting child: %s", shlex.join(self._command))
        return subprocess.Popen(
            self._command,
            env=env,
            cwd=self._repo_root,
            stdout=None,
            stderr=None,
        )

    def _stop_child(self, graceful_timeout: int = _GRACEFUL_TIMEOUT) -> Optional[int]:
        with self._child_lock:
            child = self._child
            if child is None:
                return None
            self._child = None

        if child.poll() is not None:
            return child.returncode

        LOGGER.info("Stopping child (PID %d)", child.pid)
        try:
            child.send_signal(signal.SIGTERM)
        except OSError:
            return child.returncode

        try:
            child.wait(timeout=graceful_timeout)
        except subprocess.TimeoutExpired:
            LOGGER.warning("Child did not exit after %ds, sending SIGKILL", graceful_timeout)
            try:
                child.kill()
                child.wait(timeout=5)
            except OSError:
                pass

        return child.returncode

    def _restart_child(self, reason: str) -> None:
        with self._child_lock:
            now = time.monotonic()
            if now - self._last_restart_time < _RESTART_DEBOUNCE:
                LOGGER.debug("Restart debounced (reason: %s)", reason)
                return
            self._last_restart_time = now
            self._deliberate_restart = True

            old_child = self._child
            self._child = None

        if old_child is not None:
            LOGGER.info("Restarting child: %s", reason)
            if old_child.poll() is None:
                try:
                    old_child.send_signal(signal.SIGTERM)
                    old_child.wait(timeout=_GRACEFUL_TIMEOUT)
                except (OSError, subprocess.TimeoutExpired):
                    try:
                        old_child.kill()
                        old_child.wait(timeout=5)
                    except OSError:
                        pass

        if not self._shutdown_event.is_set():
            with self._child_lock:
                self._child = self._start_child()

    def _on_file_change(self, paths: set) -> None:
        names = [p.name for p in paths]
        self._restart_child(f"file changed: {', '.join(names[:3])}")

    def _heartbeat_loop(self) -> None:
        while not self._shutdown_event.is_set():
            try:
                self._api.runners.heartbeat(
                    self._runner_id, capabilities=["jobs", "bridge"]
                )
            except ApiError as e:
                if e.status_code == 410:
                    LOGGER.info("Runner deregistered (410), shutting down")
                    self._shutdown_event.set()
                    return
                LOGGER.debug("Heartbeat error (API %s)", e.status_code, exc_info=True)
            except Exception:
                LOGGER.debug("Heartbeat error", exc_info=True)

            self._shutdown_event.wait(_HEARTBEAT_INTERVAL)

    def _install_signal_handlers(self) -> None:
        def handler(signum: int, frame: object) -> None:
            LOGGER.info("Received signal %s, shutting down", signum)
            self._shutdown_event.set()

        try:
            signal.signal(signal.SIGTERM, handler)
            signal.signal(signal.SIGINT, handler)
        except ValueError:
            LOGGER.warning("Cannot install signal handlers outside main thread")
