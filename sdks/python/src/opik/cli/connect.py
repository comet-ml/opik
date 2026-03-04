import logging
import os
import platform
import threading
import uuid
from typing import Optional

import click
import httpx

from opik import httpx_client
from opik.config import OpikConfig
from opik.rest_api.client import OpikApi
from opik.runner import (
    agents_registry,
    runner_loop,
    state,
)

LOGGER = logging.getLogger(__name__)


@click.command()
@click.option("--pair", "pair_code", default=None, help="Pairing code for the runner.")
@click.option("--name", default=None, help="Runner name.")
@click.pass_context
def connect(
    ctx: click.Context,
    pair_code: Optional[str],
    name: Optional[str],
) -> None:
    """Connect a local runner to Opik and start processing jobs."""
    runner_state = state.RunnerState.load()
    if runner_state is not None:
        pid = runner_state.pid
        if _is_process_alive(pid):
            click.echo(
                f"Runner already running (PID {pid}). Use 'opik disconnect' first."
            )
            raise SystemExit(1)
        state.RunnerState.clear()

    config = OpikConfig()
    api_key = ctx.obj.get("api_key") if ctx.obj else None

    http_client = httpx_client.get(
        workspace=config.workspace,
        api_key=api_key or config.api_key,
        check_tls_certificate=True,
        compress_json_requests=False,
    )

    api = OpikApi(
        base_url=config.url_override,
        api_key=api_key or config.api_key,
        workspace_name=config.workspace,
        httpx_client=http_client,
    )

    try:
        runner_name = name or f"{platform.node()}-{uuid.uuid4().hex[:6]}"
        # Server returns 201 with runner_id in the Location header, no JSON body.
        resp = api.runners.with_raw_response.connect_runner(
            runner_name=runner_name,
            pairing_code=pair_code,
        )
        runner_id = resp.headers["location"].rsplit("/", 1)[-1]

        runner_state = state.RunnerState(
            runner_id=runner_id,
            pid=os.getpid(),
            name=name or "",
            base_url=config.url_override,
        )
        runner_state.save()

        agents = agents_registry.load_agents()
        if agents:
            payload = {
                name: {k: v for k, v in a.to_dict().items() if k != "name"}
                for name, a in agents.items()
            }
            api.runners.register_agents(runner_id, request=payload)

        click.echo(f"Runner connected (ID: {runner_id}). Listening for jobs...")

        shutdown_event = threading.Event()
        loop = runner_loop.RunnerLoop(api, runner_id, shutdown_event)
        loop.run()
    except httpx.ConnectError:
        click.echo(
            f"Error: Could not connect to Opik at {config.url_override}. "
            "Check that the backend is running."
        )
        raise SystemExit(1)
    finally:
        http_client.close()
        state.RunnerState.clear()
        click.echo("Runner disconnected.")


def _is_process_alive(pid: int) -> bool:
    # Signal 0 doesn't kill — it only checks whether the process exists.
    # Works on both Unix (POSIX) and Windows.
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False
