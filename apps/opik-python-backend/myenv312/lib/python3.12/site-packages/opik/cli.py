"""CLI tool for Opik."""

import logging
import sys
from importlib import metadata

import click
from rich.console import Console

from opik import healthcheck as opik_healthcheck
from opik.configurator import configure as opik_configure, interactive_helpers

console = Console()

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
    "--use-local",
    is_flag=True,
    default=False,
    help="Flag to configure the Opik Python SDK for local Opik deployments.",
)
@click.option(
    "-y",
    "--yes",
    is_flag=True,
    default=False,
    help="Flag to automatically answer `yes` whenever a user approval might be required",
)
def configure(use_local: bool, yes: bool) -> None:
    """
    Create a configuration file for the Opik Python SDK, if a configuration file already exists, it will be overwritten.
    This is also available as a function in the Python SDK.
    """
    automatic_approvals = yes

    if use_local:
        opik_configure.configure(
            use_local=True, force=True, automatic_approvals=automatic_approvals
        )
    else:
        deployment_type_choice = interactive_helpers.ask_user_for_deployment_type()

        if deployment_type_choice == interactive_helpers.DeploymentType.CLOUD:
            configurator = opik_configure.OpikConfigurator(
                url=opik_configure.OPIK_BASE_URL_CLOUD,
                use_local=False,
                force=True,
                self_hosted_comet=False,
                automatic_approvals=automatic_approvals,
            )
        elif deployment_type_choice == interactive_helpers.DeploymentType.SELF_HOSTED:
            configurator = opik_configure.OpikConfigurator(
                use_local=False,
                force=True,
                self_hosted_comet=True,
                automatic_approvals=automatic_approvals,
            )
        elif deployment_type_choice == interactive_helpers.DeploymentType.LOCAL:
            configurator = opik_configure.OpikConfigurator(
                use_local=True,
                force=True,
                self_hosted_comet=False,
                automatic_approvals=automatic_approvals,
            )
        else:
            LOGGER.error("Unknown deployment type was selected. Exiting.")
            exit(1)

        configurator.configure()


@cli.command(context_settings={"ignore_unknown_options": True})
@click.option(
    "--ollama",
    is_flag=True,
    help="Run as a proxy server for Ollama",
)
@click.option(
    "--ollama-host",
    default="http://localhost:11434",
    help="Ollama server URL when using --ollama-proxy",
    show_default=True,
)
@click.option(
    "--lm-studio",
    is_flag=True,
    help="Run as a proxy server for LM Studio",
)
@click.option(
    "--lm-studio-host",
    default="http://localhost:1234",
    help="LM Studio server URL when using --lm-studio-proxy",
    show_default=True,
)
@click.option(
    "--host",
    default="localhost",
    help="Host to bind to",
    show_default=True,
)
@click.option(
    "--port",
    default=7860,
    help="Port to bind to",
    show_default=True,
)
def proxy(
    ollama: bool,
    ollama_host: str,
    lm_studio: bool,
    lm_studio_host: str,
    host: str,
    port: int,
) -> None:
    """Start the Opik server."""
    try:
        import fastapi  # noqa
        import uvicorn  # noqa
        import httpx  # noqa
        import rich  # noqa
    except ImportError:
        raise click.ClickException(
            "Proxy server dependencies not found. Please install them with: pip install opik[proxy]"
        )

    if not ollama and not lm_studio:
        click.echo(
            "Error: Either --ollama or --lm-studio must be specified",
            err=True,
        )
        sys.exit(1)

    if ollama and lm_studio:
        click.echo("Error: Cannot specify both --ollama and --lm-studio", err=True)
        sys.exit(1)

    if ollama:
        llm_server_host = ollama_host
        llm_server_type = "Ollama"
    else:  # lm_studio_proxy
        llm_server_host = lm_studio_host
        llm_server_type = "LM Studio"

    from opik.forwarding_server.app import create_app
    from opik.forwarding_server.utils import print_server_startup_message
    import uvicorn

    app = create_app(llm_server_host)
    print_server_startup_message(
        host=host,
        port=port,
        llm_server_type=llm_server_type,
        llm_server_host=llm_server_host,
    )
    uvicorn.run(
        app, host=host, port=port, log_level="error"
    )  # Reduce uvicorn logging to keep output clean


@cli.command(context_settings={"ignore_unknown_options": True})
@click.option(
    "--show-installed-packages",
    is_flag=True,
    default=False,
    help="Print the list of installed packages to the console.",
)
def healthcheck(show_installed_packages: bool = True) -> None:
    """
    Performs a health check of the application, including validation of configuration,
    verification of library installations, and checking the availability of the backend workspace.
    Prints all relevant information to assist in debugging and diagnostics.
    """
    opik_healthcheck.run(show_installed_packages)


if __name__ == "__main__":
    cli()
