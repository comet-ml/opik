"""CLI tool for Opik."""

from importlib import metadata

import click

from opik_installer import opik_server

__version__: str = "0.0.0+dev"
if __package__:
    __version__ = metadata.version(__package__)

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(invoke_without_command=True, context_settings=CONTEXT_SETTINGS)
@click.version_option(__version__, *("--version", "-v"))
def cli() -> None:
    """CLI tool for Opik."""


cli.add_command(opik_server, name="server")


if __name__ == "__main__":
    cli()
