"""CLI tool for Opik."""

from importlib import metadata

import click
import questionary

from opik.configurator import configure as opik_configure

__version__: str = "0.0.0+dev"
if __package__:
    __version__ = metadata.version(__package__)

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@click.group(invoke_without_command=True, context_settings=CONTEXT_SETTINGS)
@click.version_option(__version__, *("--version", "-v"))
def cli() -> None:
    """CLI tool for Opik."""


@cli.command(context_settings={"ignore_unknown_options": True})
@click.option(
    "--use_local",
    is_flag=True,
    default=False,
    help="Flag to configure the Opik Python SDK for local Opik deployments.",
)
def configure(use_local: bool) -> None:
    """
    Create a configuration file for the Opik Python SDK, if a configuration file already exists, it will be overwritten.
    This is also available as a function in the Python SDK.
    """
    if use_local:
        opik_configure.configure(use_local=True, force=True)
    else:
        deployment_type_choice = questionary.select(
            "Which Opik deployment do you want to log your traces to?",
            choices=["Opik Cloud", "Local deployment"],
        ).unsafe_ask()

        if deployment_type_choice == "Opik Cloud":
            opik_configure.configure(use_local=False, force=True)
        else:
            opik_configure.configure(use_local=True, force=True)


if __name__ == "__main__":
    cli()
