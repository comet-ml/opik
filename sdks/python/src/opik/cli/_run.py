"""Shared CLI session runner for connect and endpoint commands."""

from typing import List, Optional

import click
import httpx
import sentry_sdk

from opik import Opik
from opik.rest_api.core.api_error import ApiError
from opik.runner.tui import RunnerTUI

from .pairing import (
    RunnerType,
    generate_runner_name,
    launch_supervisor,
    run_headless,
    run_pairing,
)


def _capture_cli_error(
    exception: Exception, error_type: str, **details: object
) -> None:
    sentry_sdk.set_tag("error_type", error_type)
    context = {k: v for k, v in details.items() if v is not None}
    cause = exception.__cause__ or exception.__context__
    if cause is not None:
        context["caused_by"] = type(cause).__name__
        if hasattr(cause, "status_code"):
            context["caused_by_status_code"] = cause.status_code
    sentry_sdk.set_context("cli_error", context)
    sentry_sdk.capture_exception(exception)


def run_cli_session(
    ctx: click.Context,
    project_name: str,
    name: Optional[str],
    runner_type: RunnerType,
    command: Optional[List[str]] = None,
    watch: Optional[bool] = None,
    headless: bool = False,
) -> None:
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    client = Opik(api_key=api_key, _show_misconfiguration_message=False)
    api = client.rest_client
    tui = RunnerTUI()

    # Global scope so background threads (heartbeat, bridge-poll) inherit the tag.
    # Safe because the CLI process runs one command and exits.
    sentry_sdk.set_tag("cli_command", f"opik-{runner_type.value}")

    try:
        runner_name = generate_runner_name(name)
        tui.start()
        tui.print_banner(project_name, url=client.config.url_override)

        if headless:
            result = run_headless(
                api=api,
                project_name=project_name,
                runner_name=runner_name,
                runner_type=runner_type,
            )
        else:
            result = run_pairing(
                api=api,
                project_name=project_name,
                runner_name=runner_name,
                runner_type=runner_type,
                base_url=client.config.url_override,
                workspace=client.config.workspace,
                tui=tui,
            )

        launch_supervisor(
            result, api, tui, runner_type=runner_type, command=command, watch=watch
        )
    except KeyboardInterrupt:
        raise SystemExit(130)
    except click.ClickException as e:
        _capture_cli_error(e, error_type="ClickException", message=e.format_message())
        raise
    except ApiError as e:
        _capture_cli_error(
            e,
            error_type="ApiError",
            message=str(e.body) if e.body else None,
            status_code=e.status_code,
        )
        click.echo(f"Error: {e.body}" if e.body else f"Error: {e.status_code}")
        raise SystemExit(1)
    except httpx.ConnectError as e:
        _capture_cli_error(
            e,
            error_type="ConnectError",
            message=str(e),
            url=client.config.url_override,
        )
        click.echo(
            f"Error: Could not connect to Opik at {client.config.url_override}. "
            "Check that the backend is running."
        )
        raise SystemExit(1)
    except OSError as e:
        _capture_cli_error(e, error_type="OSError", message=str(e))
        cmd_name = command[0] if command else "unknown"
        click.echo(f"Error: Could not execute command '{cmd_name}': {e}")
        raise SystemExit(1)
    except Exception as e:
        _capture_cli_error(e, error_type=type(e).__name__, message=str(e))
        raise
    finally:
        tui.stop()
        client.end()
