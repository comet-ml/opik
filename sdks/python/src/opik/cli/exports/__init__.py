"""Download command for Opik CLI."""

from typing import Optional

import click

from .dataset import export_dataset_command
from .experiment import export_experiment_command
from .prompt import export_prompt_command
from .project import export_project_command

EXPORT_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(name="export", context_settings=EXPORT_CONTEXT_SETTINGS)
@click.argument("workspace", type=str)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def export_group(ctx: click.Context, workspace: str, api_key: Optional[str]) -> None:
    """Export data from Opik workspace."""
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace
    # Use API key from this command or from parent context
    ctx.obj["api_key"] = api_key or (
        ctx.parent.obj.get("api_key") if ctx.parent and ctx.parent.obj else None
    )


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
export_group.format_commands = format_commands.__get__(export_group, type(export_group))


# Add the subcommands
export_group.add_command(export_dataset_command)
export_group.add_command(export_experiment_command)
export_group.add_command(export_prompt_command)
export_group.add_command(export_project_command)
