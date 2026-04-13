"""CLI command: opik connect — standalone bridge daemon."""

from typing import Optional

import click

from ._run import run_cli_session
from .pairing import RunnerType


@click.command()
@click.option("--project", "project_name", required=True, help="Opik project name.")
@click.option("--name", default=None, help="Runner name.")
@click.pass_context
def connect(
    ctx: click.Context,
    project_name: str,
    name: Optional[str],
) -> None:
    """Connect a local bridge daemon to Opik."""
    run_cli_session(
        ctx=ctx,
        project_name=project_name,
        name=name,
        runner_type=RunnerType.CONNECT,
    )
