"""Download command for Opik CLI."""

import logging
from typing import Optional

import click
from rich.logging import RichHandler

from .all import export_all_command
from .dataset import export_dataset_command
from .experiment import export_experiment_command
from .prompt import export_prompt_command
from .project import export_project_command

EXPORT_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(
    name="export", context_settings=EXPORT_CONTEXT_SETTINGS, invoke_without_command=True
)
@click.argument("workspace", type=str)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def export_group(ctx: click.Context, workspace: str, api_key: Optional[str]) -> None:
    """Export data from Opik workspace.

    This command allows you to export specific data from an Opik workspace to local files.
    Supported data types include datasets, projects, experiments, and prompts.

    \b
    General Usage:
        opik export WORKSPACE ITEM NAME [OPTIONS]

    \b
    Data Types (ITEM):
        all          Export everything: datasets, prompts, projects, and experiments
        dataset      Export a dataset by exact name (exports dataset definition and items)
        project      Export a project by name or ID (exports project traces and metadata)
        experiment   Export an experiment by name or ID (exports experiment configuration and results)
        prompt       Export a prompt by exact name (exports prompt templates and versions)

    \b
    Common Options:
        --path, -p       Directory to save exported data (default: opik_exports)
        --format         Export format: json or csv (default: json)
        --max-results    Maximum number of items to export (varies by data type)
        --force          Re-download items even if they already exist locally
        --debug          Show detailed information about the export process

    \b
    Examples:
        # Export everything in the workspace
        opik export my-workspace all

        # Export only datasets and prompts
        opik export my-workspace all --include datasets,prompts

        # Export a specific dataset
        opik export my-workspace dataset "my-dataset"

        # Export a project with OQL filter
        opik export my-workspace project "my-project" --filter "status:completed"

        # Export an experiment with dataset filter (by name or ID)
        opik export my-workspace experiment "my-experiment" --dataset "my-dataset"
        opik export my-workspace experiment "01234567-89ab-cdef-0123-456789abcdef" --dataset "my-dataset"

        # Export in CSV format to a specific directory
        opik export my-workspace prompt "my-template" --format csv --path ./custom-exports
    """
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace
    # Use API key from this command or from parent context
    ctx.obj["api_key"] = api_key or (
        ctx.parent.obj.get("api_key") if ctx.parent and ctx.parent.obj else None
    )

    # During bulk export the opik SDK emits deprecation warnings via its own
    # StreamHandler (format "OPIK: <message>"), which writes to stderr and
    # corrupts the Rich progress bar display.  Replace that handler with a
    # RichHandler so any SDK log output is routed through the shared console
    # (and therefore properly interleaved with the progress bars).
    # Only ERROR and above are shown; WARNING-level deprecation notices are
    # noise during export and the export code reports real errors itself.
    from .utils import console as _console

    _opik_logger = logging.getLogger("opik")
    # Snapshot existing handlers so we can restore them when the CLI command
    # exits (important: CliRunner-based unit tests share the global logger state
    # across test cases, so any mutation must be undone on context teardown).
    _original_handlers = list(_opik_logger.handlers)

    def _restore_opik_handlers() -> None:
        for _h in list(_opik_logger.handlers):
            _opik_logger.removeHandler(_h)
        for _h in _original_handlers:
            _opik_logger.addHandler(_h)

    ctx.call_on_close(_restore_opik_handlers)

    # Remove existing StreamHandlers (SDK installs one with "OPIK: " prefix).
    # Also remove any RichHandlers not bound to _console — those would route SDK
    # logs to a different console and bypass our progress-bar-aware output.
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

    # If no subcommand was invoked, show helpful error
    if ctx.invoked_subcommand is None:
        available_items = ", ".join(
            sorted(["all", "dataset", "experiment", "prompt", "project"])
        )
        click.echo(
            f"Error: Missing ITEM.\n\n"
            f"Available items: {available_items}\n\n"
            f"Usage: opik export {workspace} ITEM NAME [OPTIONS]\n\n"
            f"Examples:\n"
            f"  opik export {workspace} all\n"
            f'  opik export {workspace} dataset "my-dataset"\n'
            f'  opik export {workspace} project "my-project"\n'
            f'  opik export {workspace} experiment "my-experiment"\n'
            f'  opik export {workspace} prompt "my-template"\n\n'
            f"Run 'opik export {workspace} --help' for more information.",
            err=True,
        )
        ctx.exit(2)


# Set subcommand metavar to ITEM instead of COMMAND
export_group.subcommand_metavar = "ITEM [ARGS]..."


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


# Override format_commands method
setattr(
    export_group,
    "format_commands",
    format_commands.__get__(export_group, type(export_group)),
)


# Add the subcommands
export_group.add_command(export_all_command)
export_group.add_command(export_dataset_command)
export_group.add_command(export_experiment_command)
export_group.add_command(export_prompt_command)
export_group.add_command(export_project_command)
