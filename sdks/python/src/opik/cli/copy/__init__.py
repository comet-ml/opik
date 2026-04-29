"""Copy command for Opik CLI.

The ``opik copy`` group orchestrates a same-instance copy of one asset (and
its related entities) from a source location into a destination project.
v1 ships ``copy dataset`` only; ``copy prompt`` / ``copy experiment`` slot in
later behind the same group.

Under the hood, each subcommand:
1. Exports the asset (and its related entities) into a persistent run dir
   under ``~/.opik/copy-runs/`` using the existing ``opik export`` machinery.
2. Imports it back via ``opik import``, threading a ``destination_project``
   override through so all entities land in the chosen project.
"""

import logging
from typing import Optional

import click
from rich.logging import RichHandler

from .dataset import copy_dataset_command

COPY_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(
    name="copy", context_settings=COPY_CONTEXT_SETTINGS, invoke_without_command=True
)
@click.argument("workspace", type=str)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def copy_group(ctx: click.Context, workspace: str, api_key: Optional[str]) -> None:
    """Copy assets between projects on the same Opik instance.

    Copies an asset (and its related entities) from a source location into
    a destination project on the same Opik instance, in a single command.

    \b
    General Usage:
        opik copy WORKSPACE ITEM NAME --destination-project NAME [OPTIONS]

    \b
    Data Types (ITEM):
        dataset      Copy a dataset (and its experiments + traces + spans by
                     default) into a destination project.

    \b
    Common Options:
        --destination-project   Required. Project name to copy into. The
                                backend auto-creates the project if it does
                                not yet exist.
        --source-project        Optional. Scopes which experiments tag along.
                                Omit to pull every experiment that references
                                the source dataset across the workspace.
        --exclude-experiments   Copy only the dataset definition + items.
        --dry-run               Preview the export pass without writing to
                                the destination.
        --debug                 Show detailed information; keep the run dir
                                on success for inspection.

    \b
    Examples:
        # Copy a workspace-level dataset into Project B (with experiments + traces).
        opik copy my-workspace dataset "MyDataset" --destination-project "Project B"

        # Copy from one project into another, only the dataset definition + items.
        opik copy my-workspace dataset "MyDataset" \\
            --source-project "Project A" \\
            --destination-project "Project B" \\
            --exclude-experiments
    """
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace
    ctx.obj["api_key"] = api_key or (
        ctx.parent.obj.get("api_key") if ctx.parent and ctx.parent.obj else None
    )

    # Mirror the export group's logger swap so SDK deprecation messages don't
    # corrupt the Rich progress bars during the export → import passes.
    from ..exports.utils import console as _console

    _opik_logger = logging.getLogger("opik")
    _original_handlers = list(_opik_logger.handlers)

    def _restore_opik_handlers() -> None:
        for _h in list(_opik_logger.handlers):
            _opik_logger.removeHandler(_h)
        for _h in _original_handlers:
            _opik_logger.addHandler(_h)

    ctx.call_on_close(_restore_opik_handlers)

    for _h in list(_opik_logger.handlers):
        if isinstance(_h, RichHandler):
            if getattr(_h, "console", None) is not _console:
                _opik_logger.removeHandler(_h)
        elif isinstance(_h, logging.StreamHandler):
            _opik_logger.removeHandler(_h)
    if not any(isinstance(h, RichHandler) for h in _opik_logger.handlers):
        _rich_handler = RichHandler(
            console=_console,
            show_time=False,
            show_path=False,
            markup=False,
        )
        _rich_handler.setLevel(logging.ERROR)
        _opik_logger.addHandler(_rich_handler)

    if ctx.invoked_subcommand is None:
        available_items = ", ".join(sorted(["dataset"]))
        click.echo(
            f"Error: Missing ITEM.\n\n"
            f"Available items: {available_items}\n\n"
            f"Usage: opik copy {workspace} ITEM NAME --destination-project NAME [OPTIONS]\n\n"
            f"Examples:\n"
            f'  opik copy {workspace} dataset "MyDataset" --destination-project "Project B"\n\n'
            f"Run 'opik copy {workspace} --help' for more information.",
            err=True,
        )
        ctx.exit(2)


copy_group.subcommand_metavar = "ITEM [ARGS]..."


def format_commands(
    self: click.Group, ctx: click.Context, formatter: click.HelpFormatter
) -> None:
    """Override to change 'Commands' heading to 'Items'."""
    commands = []
    for subcommand in self.list_commands(ctx):
        cmd = self.get_command(ctx, subcommand)
        if cmd is None or cmd.hidden:
            continue
        commands.append((subcommand, cmd))

    if len(commands):
        limit = formatter.width - 6 - max(len(cmd[0]) for cmd in commands)
        rows = []
        for subcommand, cmd in commands:
            help = cmd.get_short_help_str(limit)
            rows.append((subcommand, help))

        if rows:
            with formatter.section("Items"):
                formatter.write_dl(rows)


setattr(
    copy_group,
    "format_commands",
    format_commands.__get__(copy_group, type(copy_group)),
)


copy_group.add_command(copy_dataset_command)
