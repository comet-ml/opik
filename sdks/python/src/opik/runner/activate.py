"""Runner activation — called after the user's module loads to start the in-process loop."""

import atexit
import logging
import os
import signal
import threading

from rich.console import Console

from .. import Opik
from ..rest_api.types.agent import Agent
from ..rest_api.types.param import Param
from . import registry
from .in_process_loop import InProcessRunnerLoop

LOGGER = logging.getLogger(__name__)

_started = False
_lock = threading.Lock()
_shutdown_by_signal = False


def install_signal_handlers(shutdown_event: threading.Event) -> bool:
    def handler(signum: int, frame: object) -> None:
        global _shutdown_by_signal
        _shutdown_by_signal = True
        LOGGER.info("Received signal %s, shutting down", signum)
        shutdown_event.set()

    try:
        signal.signal(signal.SIGTERM, handler)
        signal.signal(signal.SIGINT, handler)
        return True
    except ValueError:
        LOGGER.warning("Cannot install signal handlers outside main thread")
        return False


def activate_runner() -> None:
    """Start the runner loop in a background thread (non-blocking)."""
    if os.environ.get("OPIK_RUNNER_MODE") != "true":
        return

    global _started
    with _lock:
        if _started:
            return
        _started = True

    shutdown_event = threading.Event()
    if install_signal_handlers(shutdown_event):
        atexit.register(_warn_if_no_blocking_call)

    t = threading.Thread(target=_run, args=(shutdown_event,), daemon=True)
    t.start()


def _warn_if_no_blocking_call() -> None:
    if not _shutdown_by_signal:
        console = Console(stderr=True)
        console.print(
            "\n[bold yellow]Warning:[/bold yellow] The process exited without blocking. "
            "The runner needs the process to stay alive to process jobs.\n"
            "Use a server framework like uvicorn or Flask to keep the process running.\n",
        )


def _run(shutdown_event: threading.Event) -> None:
    runner_id = os.environ.get("OPIK_RUNNER_ID", "")

    if not runner_id:
        LOGGER.error(
            "OPIK_RUNNER_ID is not set. "
            "Do not set OPIK_RUNNER_MODE manually — use 'opik connect' to launch your command: "
            "opik connect --pair <code> python3 main.py"
        )
        return

    client = Opik(_show_misconfiguration_message=False)
    api = client.rest_client

    def _to_payload(entry: dict) -> dict:
        return Agent(
            description=entry.get("docstring", ""),
            language="python",
            params=[Param(name=p.name, type=p.type) for p in entry.get("params", [])],
            timeout=0,
        ).dict()

    entrypoints = registry.get_all()
    if entrypoints:
        api.runners.register_agents(
            runner_id,
            request={name: _to_payload(entry) for name, entry in entrypoints.items()},
        )

    loop = InProcessRunnerLoop(api, runner_id, shutdown_event)

    try:
        loop.run()
    finally:
        client.end()
