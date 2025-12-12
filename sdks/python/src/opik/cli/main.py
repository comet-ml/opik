"""Main CLI entry point for Opik."""

from importlib import metadata
from typing import Optional

import click

from . import configure
from . import exports
from . import harbor
from . import healthcheck
from . import imports
from . import proxy
from . import usage_report

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
cli.add_command(configure.configure)
cli.add_command(proxy.proxy)
cli.add_command(healthcheck.healthcheck)
cli.add_command(exports.export_group)
cli.add_command(imports.import_group)
cli.add_command(usage_report.usage_report)
cli.add_command(harbor.harbor)
