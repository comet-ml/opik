"""CLI tool for Opik."""

from importlib import metadata

import click

__version__: str = "0.0.0+dev"
if __package__:
    __version__ = metadata.version(__package__)

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(invoke_without_command=True, context_settings=CONTEXT_SETTINGS)
@click.version_option(__version__, *("--version", "-v"))
def cli() -> None:
    """CLI tool for Opik."""


if __name__ == "__main__":
    cli()
