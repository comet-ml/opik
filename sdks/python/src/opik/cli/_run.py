"""Shared CLI session runner for connect and endpoint commands."""

from typing import List, Optional

import click
import httpx

from opik import Opik
from opik.rest_api.core.api_error import ApiError
from opik.runner.tui import RunnerTUI

from .pairing import RunnerType, generate_runner_name, launch_supervisor, run_pairing


def run_cli_session(
    ctx: click.Context,
    project_name: str,
    name: Optional[str],
    runner_type: RunnerType,
    command: Optional[List[str]] = None,
    watch: Optional[bool] = None,
) -> None:
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    client = Opik(api_key=api_key, _show_misconfiguration_message=False)
    api = client.rest_client
    tui = RunnerTUI()

    try:
        runner_name = generate_runner_name(name)
        tui.start()
        tui.print_banner(project_name, url=client.config.url_override)

        result = run_pairing(
            api=api,
            project_name=project_name,
            runner_name=runner_name,
            runner_type=runner_type,
            base_url=client.config.url_override,
            tui=tui,
        )

        launch_supervisor(
            result, api, tui, runner_type=runner_type, command=command, watch=watch
        )
    except KeyboardInterrupt:
        raise SystemExit(130)
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
        tui.stop()
        client.end()
