"""Shared CLI session runner for connect and endpoint commands."""

import logging
import sys
from typing import List, Optional

import click
import httpx
import sentry_sdk

from opik import Opik
from opik.api_objects.rest_helpers import resolve_project_id_by_name
from opik.rest_api.core.api_error import ApiError
from opik.runner.tui import RunnerTUI

from .error_view import build_config_error_block
from .pairing import (
    RunnerType,
    generate_runner_name,
    launch_supervisor,
    run_headless,
    run_pairing,
)

LOGGER = logging.getLogger(__name__)


def _raise_runtime_error(header: str, reason: str, client: Opik) -> None:
    """Translate a runtime CLI failure into the shared config-error block.

    Lives here (not in error_view) because the data-collection side is _run's
    job: it reads from the client config and decides what reason text to show.
    """
    raise build_config_error_block(
        header,
        reason=reason,
        workspace=client.config.workspace,
        base_url=client.config.url_override,
        config_file_exists=client.config.config_file_exists,
    )


def _should_create_project(
    api: object, project_name: str, workspace: Optional[str], headless: bool
) -> bool:
    """Decide whether to auto-create the project if pairing finds it missing.

    Headless callers (e.g. Ollie spawning `opik endpoint --headless`) cannot
    answer a prompt, so we create silently. Interactive callers see a
    confirmation prompt only when the project is confirmed-missing (404).
    Any other lookup error falls through so the downstream resolver can
    surface the formatted error.
    """
    if headless:
        return True
    try:
        resolve_project_id_by_name(api, project_name)
        return False
    except ApiError as e:
        if e.status_code != 404:
            return False
    if not sys.stdin.isatty():
        return False
    workspace_label = f" in workspace '{workspace}'" if workspace else ""
    return click.confirm(
        f"Project '{project_name}'{workspace_label} does not exist. Create it?",
        default=True,
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
    workspace: Optional[str] = None,
) -> None:
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    client = Opik(
        project_name=project_name,
        api_key=api_key,
        workspace=workspace,
        _show_misconfiguration_message=False,
    )
    api = client.rest_client

    # Sentry tag is set globally so background threads (heartbeat, bridge-poll)
    # inherit it. Safe because the CLI process runs one command and exits.
    sentry_sdk.set_tag("cli_command", f"opik-{runner_type.value}")

    # Prompt before the Live TUI takes over the terminal — click.confirm and
    # Rich Live can't safely share stdout.
    create_if_missing = _should_create_project(
        api, project_name, client.config.workspace, headless
    )

    tui = RunnerTUI()

    try:
        runner_name = generate_runner_name(name)
        tui.start()
        tui.print_banner(
            project_name,
            url=client.config.url_override,
            workspace=client.config.workspace,
        )

        if headless:
            result = run_headless(
                api=api,
                project_name=project_name,
                runner_name=runner_name,
                runner_type=runner_type,
                create_if_missing=create_if_missing,
                workspace=client.config.workspace,
                base_url=client.config.url_override,
                config_file_exists=client.config.config_file_exists,
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
                create_if_missing=create_if_missing,
                config_file_exists=client.config.config_file_exists,
            )

        if not client.config.config_file_exists:
            try:
                client.config.save_to_file()
            except OSError:
                LOGGER.warning("Failed to save config file", exc_info=True)

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
        reason = str(e.body) if e.body else f"HTTP {e.status_code}"
        _raise_runtime_error("Opik API request failed", reason, client)
    except httpx.ConnectError as e:
        _capture_cli_error(
            e,
            error_type="ConnectError",
            message=str(e),
            url=client.config.url_override,
        )
        _raise_runtime_error(
            "Could not connect to Opik backend",
            "check that the backend is running",
            client,
        )
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
