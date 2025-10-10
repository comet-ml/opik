"""Main CLI entry point for Opik."""

from importlib import metadata

import click

from .configure import configure
from .download import download
from .healthcheck import healthcheck
from .proxy import proxy
from .upload import upload

__version__: str = "0.0.0+dev"
try:
    __version__ = metadata.version("opik")
except metadata.PackageNotFoundError:
    pass

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(invoke_without_command=True, context_settings=CONTEXT_SETTINGS)
@click.version_option(__version__, *("--version", "-v"))
def cli() -> None:
    """CLI tool for Opik."""


# Register all commands
cli.add_command(configure)
cli.add_command(proxy)
cli.add_command(healthcheck)
cli.add_command(download)
cli.add_command(upload)
