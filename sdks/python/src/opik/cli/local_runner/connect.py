"""CLI command: opik connect — standalone bridge daemon."""

from typing import Optional

import click

from . import _group, _run, pairing, stop


@click.group(
    cls=_group.RunnerGroup,
    invoke_without_command=True,
    context_settings={"ignore_unknown_options": True},
)
@click.pass_context
def connect(ctx: click.Context) -> None:
    """Run or stop a local bridge daemon to Opik.

    \b
    Start the daemon:
      opik connect --project X

    \b
    Stop a running daemon started in another terminal:
      opik connect stop --project X
      opik connect stop --all
    """
    if ctx.invoked_subcommand is None:
        click.echo(ctx.get_help())


@connect.command(
    _group.FALLBACK_SUBCOMMAND,
    hidden=True,
    context_settings={"ignore_unknown_options": True},
)
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
    "--non-interactive",
    is_flag=True,
    default=False,
    help="Skip the automatic `opik configure` prompt when no config file exists.",
)
@click.pass_context
def connect_run(
    ctx: click.Context,
    project_name: str,
    name: Optional[str],
    workspace: Optional[str],
    api_key: Optional[str],
    non_interactive: bool,
) -> None:
    """Connect a local bridge daemon to Opik (default action)."""
    if api_key:
        ctx.obj["api_key"] = api_key
    _run.run_cli_session(
        ctx=ctx,
        project_name=project_name,
        name=name,
        runner_type=pairing.RunnerType.CONNECT,
        workspace=workspace,
        non_interactive=non_interactive,
    )


@connect.command("stop")
@click.option(
    "--project",
    "project_name",
    default=None,
    help="Stop the connect runner bound to this project.",
)
@click.option(
    "--all",
    "stop_all",
    is_flag=True,
    default=False,
    help="Stop every local connect runner.",
)
@click.option(
    "--runner",
    "runner_id",
    default=None,
    help=(
        "Stop the connect runner with this id. "
        "Use when a project has more than one runner attached to it."
    ),
)
def connect_stop(
    project_name: Optional[str], stop_all: bool, runner_id: Optional[str]
) -> None:
    """Stop a local connect runner started in another terminal."""
    stop.do_stop(
        runner_type=pairing.RunnerType.CONNECT,
        project_name=project_name,
        all_flag=stop_all,
        runner_id_filter=runner_id,
    )
