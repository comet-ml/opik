"""CLI command: opik endpoint — local runner with user process."""

import os
import shutil
from pathlib import Path
from typing import Optional, Tuple

import click

from ._run import run_cli_session
from .pairing import RunnerType


def _validate_command(command: Tuple[str, ...]) -> None:
    if not command:
        click.echo("Error: Missing command after '--'.", err=True)
        raise SystemExit(2)

    executable = command[0]
    resolved = executable if os.path.isfile(executable) else shutil.which(executable)
    if resolved is None:
        click.echo(f"Error: Command not found: '{executable}'", err=True)
        raise SystemExit(2)
    if not os.access(resolved, os.X_OK):
        click.echo(f"Error: Command is not executable: '{executable}'", err=True)
        raise SystemExit(2)


@click.command(context_settings={"ignore_unknown_options": True})
@click.option("--project", "project_name", required=True, help="Opik project name.")
@click.option("--name", default=None, help="Runner name.")
@click.option(
    "--workspace",
    default=None,
    help="Opik workspace name. Overrides OPIK_WORKSPACE and config file.",
)
@click.option(
    "--api-key",
    default=None,
    help="Opik API key. Overrides global --api-key and OPIK_API_KEY env var.",
)
@click.option(
    "--watch/--no-watch",
    default=None,
    help="Enable/disable file watcher. Auto-detected from command if omitted.",
)
@click.option(
    "--headless",
    is_flag=True,
    default=False,
    help="Skip browser pairing and self-activate. For programmatic use.",
)
@click.argument("command", nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def endpoint(
    ctx: click.Context,
    project_name: str,
    name: Optional[str],
    workspace: Optional[str],
    api_key: Optional[str],
    watch: Optional[bool],
    headless: bool,
    command: Tuple[str, ...],
) -> None:
    """Run a local endpoint process connected to Opik."""
    if api_key:
        ctx.obj["api_key"] = api_key
    _validate_command(command)

    from opik.runner.snapshot import has_entrypoint

    if not has_entrypoint(Path.cwd()):
        raise click.ClickException(
            "No entrypoint found. Mark at least one function with "
            "@opik.track(entrypoint=True) (Python) or "
            "track({ entrypoint: true }, fn) (TypeScript) "
            "before running 'opik endpoint'."
        )

    run_cli_session(
        ctx=ctx,
        project_name=project_name,
        name=name,
        runner_type=RunnerType.ENDPOINT,
        command=list(command),
        watch=watch,
        headless=headless,
        workspace=workspace,
    )
