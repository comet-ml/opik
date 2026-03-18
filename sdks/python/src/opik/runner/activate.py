"""Runner activation — called after the user's module loads to start the in-process loop."""

import logging
import os
import threading

from rich.console import Console
from rich.text import Text

from .. import Opik
from ..rest_api.types.agent import Agent
from ..rest_api.types.param import Param
from . import prefixed_output, registry
from .in_process_loop import InProcessRunnerLoop

LOGGER = logging.getLogger(__name__)

_started = False
_lock = threading.Lock()


def activate_runner() -> None:
    """Start the runner loop in a background thread (non-blocking)."""
    if os.environ.get("OPIK_RUNNER_MODE") != "true":
        return

    global _started
    with _lock:
        if _started:
            return
        _started = True

    t = threading.Thread(target=_run, daemon=True)
    t.start()


def _run() -> None:
    runner_id = os.environ.get("OPIK_RUNNER_ID", "")
    project_name = os.environ.get("OPIK_PROJECT_NAME", "")

    if not runner_id:
        LOGGER.error("OPIK_RUNNER_ID not set, cannot activate runner")
        return

    _print_banner(runner_id, project_name)

    prefixed_output.install()

    client = Opik(_show_misconfiguration_message=False)
    api = client.rest_client

    def _to_payload(entry: dict) -> dict:
        return Agent(
            description=entry.get("docstring", ""),
            language="python",
            params=[Param(name=p.name, type=p.type) for p in entry.get("params", [])],
            timeout=0,
        ).dict()

    def _sync_agent(name: str) -> None:
        entry = registry.get_all().get(name)
        if entry is None:
            return
        try:
            api.runners.register_agents(runner_id, request={name: _to_payload(entry)})
        except Exception:
            LOGGER.debug("Failed to register agent '%s'", name, exc_info=True)

    registry.on_register(_sync_agent)

    entrypoints = registry.get_all()
    if entrypoints:
        api.runners.register_agents(
            runner_id,
            request={name: _to_payload(entry) for name, entry in entrypoints.items()},
        )

    LOGGER.info("Runner activated")

    shutdown_event = threading.Event()
    loop = InProcessRunnerLoop(api, runner_id, shutdown_event)

    try:
        loop.run()
    finally:
        client.end()


def _print_banner(runner_id: str, project_name: str) -> None:
    console = Console()

    info = Text()
    info.append("   ")
    info.append("\u2800\u20dd", style="rgb(224,62,45)")
    info.append("opik  ", style="bold")
    info.append(f"runner: {runner_id}", style="dim")
    if project_name:
        info.append(f"  project: {project_name}", style="dim")
    console.print(info)
    console.print()
