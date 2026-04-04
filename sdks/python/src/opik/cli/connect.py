import os
import platform
import shutil
import uuid
from pathlib import Path
from typing import Optional, Tuple

import click
import httpx

from opik import Opik
from opik.rest_api.core.api_error import ApiError
from opik.runner.supervisor import Supervisor
from opik.runner.tui import RunnerTUI


@click.command(context_settings={"ignore_unknown_options": True})
@click.option("--pair", "pair_code", required=True, help="Pairing code for the runner.")
@click.option("--name", default=None, help="Runner name.")
@click.argument("command", nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def connect(
    ctx: click.Context,
    pair_code: Optional[str],
    name: Optional[str],
    command: Tuple[str, ...],
) -> None:
    """Connect a local runner to Opik and launch a supervised process."""
    if not command:
        click.echo(
            "Error: Missing command.\n\n"
            "Usage: opik connect [OPTIONS] COMMAND [ARGS]...\n\n"
            "Example: opik connect --pair <code> python3 main.py",
            err=True,
        )
        raise SystemExit(2)

    executable = command[0]
    resolved = executable if os.path.isfile(executable) else shutil.which(executable)
    if resolved is None:
        click.echo(f"Error: Command not found: '{executable}'", err=True)
        raise SystemExit(2)
    if not os.access(resolved, os.X_OK):
        click.echo(f"Error: Command is not executable: '{executable}'", err=True)
        raise SystemExit(2)

    api_key = ctx.obj.get("api_key") if ctx.obj else None

    client = Opik(api_key=api_key, _show_misconfiguration_message=False)
    api = client.rest_client

    try:
        runner_name = name or f"{platform.node()}-{uuid.uuid4().hex[:6]}"
        resp = api.runners.connect_runner(
            runner_name=runner_name,
            pairing_code=pair_code,
        )

        runner_id = resp.runner_id
        if not runner_id:
            click.echo("Error: server did not return a runner_id")
            raise SystemExit(1)

        project_name = resp.project_name
        if not project_name:
            click.echo("Error: server did not return a project_name")
            raise SystemExit(1)

        env = {
            **os.environ,
            "OPIK_RUNNER_MODE": "true",
            "OPIK_RUNNER_ID": runner_id,
            "OPIK_PROJECT_NAME": project_name,
        }

        tui = RunnerTUI()
        tui.start()
        tui.print_banner(runner_id, project_name)

        supervisor = Supervisor(
            command=list(command),
            env=env,
            repo_root=Path.cwd(),
            runner_id=runner_id,
            api=api,
            on_child_output=lambda stream, line: tui.app_line(line, stream),
            on_child_restart=lambda reason: tui.child_restarted(reason),
            on_command_start=lambda cid, ctype, summary: tui.op_start(
                cid, ctype, summary
            ),
            on_command_end=lambda cid, success, error: tui.op_end(cid, success, error),
        )
        try:
            supervisor.run()
        finally:
            tui.stop()
    except ApiError as e:
        click.echo(f"Error: {e.body}" if e.body else f"Error: {e.status_code}")
        raise SystemExit(1)
    except httpx.ConnectError:
        config = client.config
        click.echo(
            f"Error: Could not connect to Opik at {config.url_override}. "
            "Check that the backend is running."
        )
        raise SystemExit(1)
    except OSError as e:
        click.echo(f"Error: Could not execute command '{command[0]}': {e}")
        raise SystemExit(1)
    finally:
        client.end()
