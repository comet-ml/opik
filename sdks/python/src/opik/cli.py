"""CLI tool for Opik."""

import logging
from importlib import metadata

import click
from opik.configurator import configure as opik_configure
from opik.configurator import interactive_helpers

LOGGER = logging.getLogger(__name__)

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
        deployment_type_choice = interactive_helpers.ask_user_for_deployment_type()

        if deployment_type_choice == interactive_helpers.DeploymentType.CLOUD:
            configurator = opik_configure.OpikConfigurator(
                url=opik_configure.OPIK_BASE_URL_CLOUD,
                use_local=False,
                force=True,
                self_hosted_comet=False,
            )
        elif deployment_type_choice == interactive_helpers.DeploymentType.SELF_HOSTED:
            configurator = opik_configure.OpikConfigurator(
                use_local=False,
                force=True,
                self_hosted_comet=True,
            )
        elif deployment_type_choice == interactive_helpers.DeploymentType.LOCAL:
            configurator = opik_configure.OpikConfigurator(
                use_local=True,
                force=True,
                self_hosted_comet=False,
            )
        else:
            LOGGER.error("Unknown deployment type was selected. Exiting.")
            exit(1)

        configurator.configure()


if __name__ == "__main__":
    cli()
