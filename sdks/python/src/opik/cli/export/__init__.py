"""Download command for Opik CLI."""

import click

from .dataset import export_dataset_command
from .experiment import export_experiment_command
from .prompt import export_prompt_command
from .project import export_project_command


@click.group(name="export")
@click.argument("workspace", type=str)
@click.pass_context
def export_group(ctx: click.Context, workspace: str) -> None:
    """Export data from Opik workspace."""
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace


# Add the subcommands
export_group.add_command(export_dataset_command)
export_group.add_command(export_experiment_command)
export_group.add_command(export_prompt_command)
export_group.add_command(export_project_command)
