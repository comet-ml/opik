import os
import platform
import uuid
from typing import Optional, Tuple

import click
import httpx

from opik import Opik
from opik.rest_api.core.api_error import ApiError


@click.command(context_settings={"ignore_unknown_options": True})
@click.option("--pair", "pair_code", default=None, help="Pairing code for the runner.")
@click.option("--name", default=None, help="Runner name.")
@click.argument("command", nargs=-1, type=click.UNPROCESSED, required=False)
@click.pass_context
def connect(
    ctx: click.Context,
    pair_code: Optional[str],
    name: Optional[str],
    command: Tuple[str, ...],
) -> None:
    """Connect a local runner to Opik and exec the user command."""
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

        click.echo(f"Runner connected (ID: {runner_id}).")

        if not command:
            click.echo("No command specified. Set env vars and exiting.")
            return

        env = {
            **os.environ,
            "OPIK_RUNNER_MODE": "true",
            "OPIK_RUNNER_ID": runner_id,
            "OPIK_PROJECT_NAME": project_name,
        }

        client.end()
        os.execvpe(command[0], list(command), env)
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
