"""Main CLI entry point for Opik."""

from importlib import metadata
from typing import Optional

import click

from .configure import configure
from .export import export
from .healthcheck import healthcheck
from .import_command import import_data
from .proxy import proxy
from .usage_report import usage_report  # Import from usage_report package

__version__: str = "0.0.0+dev"
try:
    __version__ = metadata.version("opik")
except metadata.PackageNotFoundError:
    pass

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(invoke_without_command=True, context_settings=CONTEXT_SETTINGS)
@click.version_option(__version__, *("--version", "-v"))
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def cli(ctx: click.Context, api_key: Optional[str]) -> None:
    """
    CLI tool for Opik.
    """
    # Store API key in context for subcommands to access
    ctx.ensure_object(dict)
    ctx.obj["api_key"] = api_key


# Register all commands
cli.add_command(configure)
cli.add_command(proxy)
cli.add_command(healthcheck)
cli.add_command(export)
cli.add_command(import_data)
cli.add_command(usage_report)
