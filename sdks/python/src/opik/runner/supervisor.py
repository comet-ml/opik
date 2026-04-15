"""Supervisor — manages child process lifecycle, bridge polling, file watching, and heartbeat."""

import logging
import shlex
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from ..cli.pairing import RunnerType
from ..rest_api.core.api_error import ApiError
from .bridge_handlers import FileLockRegistry
from .bridge_handlers.edit_file import EditFileHandler
from .bridge_handlers.exec_command import BackgroundProcessTracker, ExecHandler
from .bridge_handlers.list_files import ListFilesHandler
from .bridge_handlers.read_file import ReadFileHandler
from .bridge_handlers.search_files import SearchFilesHandler
from .bridge_handlers.write_file import WriteFileHandler
from .bridge_loop import BridgePollLoop
from .file_watcher import FileWatcher
from .snapshot import build_checklist
from .stability_guard import StabilityGuard

LOGGER = logging.getLogger(__name__)

_HEARTBEAT_INTERVAL_SECONDS = 5.0
_GRACEFUL_TIMEOUT_SECONDS = 10
_RESTART_DEBOUNCE_SECONDS = 1.0
_STDERR_MAX_LINES = 500

_RELOAD_INDICATORS = frozenset(
    {
        "--reload",
        "--debug",
        "nodemon",
        "docker",
        "docker-compose",
    }
)


def _command_has_reload(command: List[str]) -> bool:
    for token in command:
        if token in _RELOAD_INDICATORS:
            return True
    return False


class Supervisor:
    """Outer process for `opik connect`. Stays alive to manage heartbeat, bridge
    command polling, and file watching while launching the user's app as a child
    process via Popen. Restarts the child on file changes (debounced) and crashes
    (with a stability guard). Shuts down cleanly on SIGTERM/SIGINT or runner eviction."""

    def __init__(
        self,
        command: Optional[List[str]],
        env: Dict[str, str],
        repo_root: Path,
        runner_id: str,
        api: Any,
        on_child_output: Optional[Callable[[str, str], None]] = None,
        on_child_restart: Optional[Callable[[str], None]] = None,
        on_error: Optional[Callable[[str], None]] = None,
        on_command_start: Optional[Callable[[str, str, str], None]] = None,
        on_command_end: Optional[Callable[[str, bool, Optional[str]], None]] = None,
        watch: Optional[bool] = None,
        bridge_key: Optional[bytes] = None,
        runner_type: RunnerType = RunnerType.ENDPOINT,
    ) -> None:
        self._command = command
        self._env = env
        self._repo_root = repo_root
        self._runner_id = runner_id
        self._api = api
        self._on_child_output = on_child_output or self._default_output_callback
        self._on_child_restart = on_child_restart
        self._on_error = on_error
        self._on_command_start = on_command_start
        self._on_command_end = on_command_end
        self._bridge_key = bridge_key
        self._runner_type = runner_type
        if command is None:
            self._watch = False
        elif watch is None:
            self._watch = not _command_has_reload(command)
        else:
            self._watch = watch
        self._shutdown_event = threading.Event()
        self._child: Optional[subprocess.Popen] = None
        self._child_lock = threading.Lock()
        self._deliberate_restart = False
        self._guard = StabilityGuard()
        self._last_restart_time = 0.0
        self._stderr_buffer: List[str] = []
        self._stderr_lock = threading.Lock()
        self._reader_threads: List[threading.Thread] = []

    def run(self) -> None:
        self._install_signal_handlers()

        heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop, name="supervisor-heartbeat", daemon=True
        )
        heartbeat_thread.start()

        self._bg_tracker = BackgroundProcessTracker()

        if self._runner_type == RunnerType.CONNECT:
            mutation_queue = FileLockRegistry()
            handlers: Dict[str, Any] = {
                "ReadFile": ReadFileHandler(self._repo_root),
                "WriteFile": WriteFileHandler(self._repo_root, mutation_queue),
                "EditFile": EditFileHandler(self._repo_root, mutation_queue),
                "ListFiles": ListFilesHandler(self._repo_root),
                "SearchFiles": SearchFilesHandler(self._repo_root),
                "Exec": ExecHandler(self._repo_root, self._bg_tracker),
            }
            bridge_loop = BridgePollLoop(
                self._api,
                self._runner_id,
                handlers,
                self._shutdown_event,
                on_command_start=self._on_command_start,
                on_command_end=self._on_command_end,
                bridge_key=self._bridge_key,
            )
            bridge_thread = threading.Thread(
                target=bridge_loop.run, name="bridge-poll", daemon=True
            )
            bridge_thread.start()

        if self._watch:
            watcher = FileWatcher(self._repo_root, self._on_file_change)
            watcher_thread = threading.Thread(
                target=watcher.run,
                args=(self._shutdown_event,),
                name="file-watcher",
                daemon=True,
            )
            watcher_thread.start()
        else:
            LOGGER.info("File watcher disabled (framework handles restarts)")

        if self._command is not None:
            with self._child_lock:
                self._child = self._start_child()
        self._send_checklist()

        try:
            if self._command is not None:
                self._main_loop()
            else:
                LOGGER.info("Running in standalone mode (no child process)")
                self._shutdown_event.wait()
        finally:
            self._shutdown_event.set()
            self._bg_tracker.shutdown()
            if self._command is not None:
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

            should_restart = self._handle_child_exit(exit_code)
            if not should_restart:
                continue

            with self._child_lock:
                self._child = self._start_child()
            self._send_checklist()

    def _start_child(self) -> subprocess.Popen:
        assert self._command is not None
        with self._stderr_lock:
            self._stderr_buffer.clear()
        env = {**self._env}
        LOGGER.info("Starting child: %s", shlex.join(self._command))
        child = subprocess.Popen(
            self._command,
            env=env,
            cwd=self._repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        stdout_t = threading.Thread(
            target=self._read_stream,
            args=(child, child.stdout, "stdout"),
            name="child-stdout",
            daemon=True,
        )
        stderr_t = threading.Thread(
            target=self._read_stream,
            args=(child, child.stderr, "stderr"),
            name="child-stderr",
            daemon=True,
        )
        stdout_t.start()
        stderr_t.start()
        self._reader_threads = [stdout_t, stderr_t]
        return child

    def _read_stream(self, child: subprocess.Popen, stream: Any, name: str) -> None:
        try:
            for raw_line in iter(stream.readline, b""):
                if self._shutdown_event.is_set():
                    break
                try:
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\n\r")
                except Exception:
                    continue
                if name == "stderr":
                    with self._stderr_lock:
                        self._stderr_buffer.append(line)
                        if len(self._stderr_buffer) > _STDERR_MAX_LINES:
                            self._stderr_buffer = self._stderr_buffer[
                                -_STDERR_MAX_LINES:
                            ]
                self._on_child_output(name, line)
        except (ValueError, OSError):
            pass

    def _get_stderr_tail(self) -> str:
        with self._stderr_lock:
            return "\n".join(self._stderr_buffer)

    def _stop_child(
        self, graceful_timeout: int = _GRACEFUL_TIMEOUT_SECONDS
    ) -> Optional[int]:
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
            LOGGER.warning(
                "Child did not exit after %ds, sending SIGKILL", graceful_timeout
            )
            try:
                child.kill()
                child.wait(timeout=5)
            except OSError:
                pass

        for t in self._reader_threads:
            t.join(timeout=2)
        self._reader_threads = []

        return child.returncode

    def _handle_child_exit(self, exit_code: int) -> bool:
        """Handle a child process exit. Returns True if child should be restarted."""
        LOGGER.warning("Child exited with code %d", exit_code)
        stderr_tail = self._get_stderr_tail()
        self._guard.record_crash()

        if not self._guard.is_stable():
            LOGGER.error("Child crash-looping — waiting for file change to retry")
            if self._on_error:
                self._on_error("Crash loop detected — waiting for file change to retry")
            self._patch_crash_info(exit_code, stderr_tail)
            return False

        if self._on_child_restart:
            self._on_child_restart("agent process has failed")

        return True

    def _restart_child(self, reason: str) -> None:
        with self._child_lock:
            now = time.monotonic()
            if now - self._last_restart_time < _RESTART_DEBOUNCE_SECONDS:
                LOGGER.debug("Restart debounced (reason: %s)", reason)
                return
            self._last_restart_time = now
            self._deliberate_restart = True

            old_child = self._child
            self._child = None

        if old_child is not None:
            LOGGER.info("Restarting child: %s", reason)
            if self._on_child_restart:
                self._on_child_restart(reason)
            if old_child.poll() is None:
                try:
                    old_child.send_signal(signal.SIGTERM)
                    old_child.wait(timeout=_GRACEFUL_TIMEOUT_SECONDS)
                except (OSError, subprocess.TimeoutExpired):
                    try:
                        old_child.kill()
                        old_child.wait(timeout=5)
                    except OSError:
                        pass

        if not self._shutdown_event.is_set():
            with self._child_lock:
                self._child = self._start_child()
            self._send_checklist()

    def _on_file_change(self, paths: set) -> None:
        self._guard.reset()
        names = [p.name for p in paths]
        self._restart_child(f"file changed: {', '.join(names[:3])}")

    def _send_checklist(self) -> None:
        try:
            checklist = build_checklist(
                self._repo_root, self._command, self._runner_type
            )
            self._api.runners.patch_checklist(self._runner_id, request=checklist)
            LOGGER.debug(
                "Checklist sent (instrumented=%s)", checklist["instrumentation"]
            )
        except Exception:
            LOGGER.debug("Failed to send checklist", exc_info=True)

    def _patch_crash_info(self, exit_code: int, stderr_tail: str) -> None:
        try:
            self._api.runners.patch_checklist(
                self._runner_id,
                request={
                    "child_status": "crashed",
                    "last_crash": {
                        "exit_code": exit_code,
                        "stderr_tail": stderr_tail,
                    },
                },
            )
        except Exception:
            LOGGER.debug("Failed to patch crash info", exc_info=True)

    def _default_output_callback(self, stream: str, line: str) -> None:
        target = sys.stderr if stream == "stderr" else sys.stdout
        try:
            print(line, file=target, flush=True)
        except (BrokenPipeError, OSError):
            pass

    def _heartbeat_loop(self) -> None:
        while not self._shutdown_event.is_set():
            try:
                caps = (
                    ["bridge"] if self._runner_type == RunnerType.CONNECT else ["jobs"]
                )
                self._api.runners.heartbeat(self._runner_id, capabilities=caps)
            except ApiError as e:
                if e.status_code == 410:
                    LOGGER.info("Runner deregistered (410), shutting down")
                    self._shutdown_event.set()
                    return
                LOGGER.debug("Heartbeat error (API %s)", e.status_code, exc_info=True)
            except Exception:
                LOGGER.debug("Heartbeat error", exc_info=True)

            self._shutdown_event.wait(_HEARTBEAT_INTERVAL_SECONDS)

    def _install_signal_handlers(self) -> None:
        def handler(signum: int, frame: object) -> None:
            LOGGER.info("Received signal %s, shutting down", signum)
            self._shutdown_event.set()

        try:
            signal.signal(signal.SIGTERM, handler)
            signal.signal(signal.SIGINT, handler)
        except ValueError:
            LOGGER.warning("Cannot install signal handlers outside main thread")
