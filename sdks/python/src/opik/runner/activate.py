"""Runner activation — called after the user's module loads to start the in-process loop."""

import logging
import os
import threading

from rich.console import Console
from rich.text import Text

from .. import httpx_client
from ..config import OpikConfig
from ..rest_api.client import OpikApi
from . import registry
from .in_process_loop import InProcessRunnerLoop

LOGGER = logging.getLogger(__name__)


def activate_runner() -> None:
    runner_mode = os.environ.get("OPIK_RUNNER_MODE")
    if runner_mode != "true":
        return

    runner_id = os.environ.get("OPIK_RUNNER_ID", "")
    project_name = os.environ.get("OPIK_PROJECT_NAME", "")

    if not runner_id:
        LOGGER.error("OPIK_RUNNER_ID not set, cannot activate runner")
        return

    _print_banner(runner_id, project_name)

    from . import prefixed_output

    prefixed_output.install()

    config = OpikConfig()

    http_client = httpx_client.get(
        workspace=config.workspace,
        api_key=config.api_key,
        check_tls_certificate=True,
        compress_json_requests=False,
    )

    api = OpikApi(
        base_url=config.url_override,
        api_key=config.api_key,
        workspace_name=config.workspace,
        httpx_client=http_client,
    )

    entrypoints = registry.get_all()
    if entrypoints:
        payload = {
            name: {
                "description": entry.get("docstring", ""),
                "language": "python",
                "params": [
                    {"name": p.name, "type": p.type} for p in entry.get("params", [])
                ],
                "timeout": 0,
            }
            for name, entry in entrypoints.items()
        }
        api.runners.register_agents(runner_id, request=payload)

    LOGGER.info(
        "Runner activated with %d entrypoint(s): %s",
        len(entrypoints),
        ", ".join(entrypoints.keys()) if entrypoints else "(none)",
    )

    shutdown_event = threading.Event()
    loop = InProcessRunnerLoop(api, runner_id, shutdown_event)

    try:
        loop.run()
    finally:
        http_client.close()


def _print_banner(runner_id: str, project_name: str) -> None:
    console = Console()
    width = console.width or 80

    line = Text("\u2500" * width, style="rgb(128,128,128)")
    console.print(line)

    info = Text()
    info.append("   ")
    info.append("\u2800\u20dd", style="rgb(224,62,45)")
    info.append("opik  ", style="bold")
    info.append(f"runner: {runner_id}", style="dim")
    if project_name:
        info.append(f"  project: {project_name}", style="dim")
    console.print(info)
    console.print()
