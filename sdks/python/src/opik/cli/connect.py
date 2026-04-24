"""CLI command: opik connect — standalone bridge daemon."""

from typing import Optional

import click

from ._run import run_cli_session
from .pairing import RunnerType


@click.command()
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
@click.pass_context
def connect(
    ctx: click.Context,
    project_name: str,
    name: Optional[str],
    workspace: Optional[str],
    api_key: Optional[str],
) -> None:
    """Connect a local bridge daemon to Opik."""
    if api_key:
        ctx.obj["api_key"] = api_key
    run_cli_session(
        ctx=ctx,
        project_name=project_name,
        name=name,
        runner_type=RunnerType.CONNECT,
        workspace=workspace,
    )
