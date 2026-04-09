import logging
import os
import platform
import shutil
import uuid
from pathlib import Path
from typing import Optional, Tuple

import click
import httpx

from opik import Opik
from opik.rest_api.client import OpikApi
from opik.rest_api.core.api_error import ApiError
from opik.runner.supervisor import Supervisor
from opik.runner.tui import RunnerTUI


def _validate_command(command: Tuple[str, ...]) -> None:
    if not command:
        return

    executable = command[0]
    resolved = executable if os.path.isfile(executable) else shutil.which(executable)
    if resolved is None:
        click.echo(f"Error: Command not found: '{executable}'", err=True)
        raise SystemExit(2)
    if not os.access(resolved, os.X_OK):
        click.echo(f"Error: Command is not executable: '{executable}'", err=True)
        raise SystemExit(2)


def _register_runner(
    api: OpikApi, name: Optional[str], pair_code: str
) -> Tuple[str, str]:
    runner_name = name or f"{platform.node()}-{uuid.uuid4().hex[:6]}"
    resp = api.runners.connect_runner(
        runner_name=runner_name,
        pairing_code=pair_code,
    )
    if not resp.runner_id:
        click.echo("Error: server did not return a runner_id")
        raise SystemExit(1)
    if not resp.project_name:
        click.echo("Error: server did not return a project_name")
        raise SystemExit(1)
    return resp.runner_id, resp.project_name


@click.command(context_settings={"ignore_unknown_options": True})
@click.option("--pair", "pair_code", required=True, help="Pairing code for the runner.")
@click.option("--name", default=None, help="Runner name.")
@click.option(
    "--watch/--no-watch",
    default=None,
    help="Enable/disable file watcher. Auto-detected from command (e.g. --reload disables it).",
)
@click.argument("command", nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def connect(
    ctx: click.Context,
    pair_code: str,
    name: Optional[str],
    watch: Optional[bool],
    command: Tuple[str, ...],
) -> None:
    """Connect a local runner to Opik and launch a supervised process."""
    _validate_command(command)

    api_key = ctx.obj.get("api_key") if ctx.obj else None
    client = Opik(api_key=api_key, _show_misconfiguration_message=False)
    api = client.rest_client

    try:
        runner_id, project_name = _register_runner(api, name, pair_code)

        env = {
            **os.environ,
            "OPIK_RUNNER_MODE": "true",
            "OPIK_RUNNER_ID": runner_id,
            "OPIK_PROJECT_NAME": project_name,
        }

        tui = RunnerTUI()
        tui.start()
        tui.print_banner(runner_id, project_name, url=client.config.url_override)

        # Suppress OPIK log lines from leaking into the TUI
        opik_logger = logging.getLogger("opik")
        opik_logger.handlers = [
            h
            for h in opik_logger.handlers
            if not isinstance(h, logging.StreamHandler)
            or isinstance(h, logging.FileHandler)
        ]

        supervisor = Supervisor(
            command=list(command) if command else None,
            env=env,
            repo_root=Path.cwd(),
            runner_id=runner_id,
            api=api,
            on_child_output=tui.app_line,
            on_child_restart=tui.child_restarted,
            on_error=tui.error,
            on_command_start=tui.op_start,
            on_command_end=tui.op_end,
            watch=watch,
        )
        try:
            supervisor.run()
        finally:
            tui.stop()
    except ApiError as e:
        click.echo(f"Error: {e.body}" if e.body else f"Error: {e.status_code}")
        raise SystemExit(1)
    except httpx.ConnectError:
        click.echo(
            f"Error: Could not connect to Opik at {client.config.url_override}. "
            "Check that the backend is running."
        )
        raise SystemExit(1)
    except OSError as e:
        cmd_name = command[0] if command else "unknown"
        click.echo(f"Error: Could not execute command '{cmd_name}': {e}")
        raise SystemExit(1)
    finally:
        client.end()
