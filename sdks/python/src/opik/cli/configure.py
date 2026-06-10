"""Configure command for Opik CLI."""

import logging
from typing import Optional

import click

from opik.configurator import configure as opik_configure, interactive_helpers

LOGGER = logging.getLogger(__name__)


def run_interactive_configure(
    use_local: bool = False,
    automatic_approvals: bool = False,
    install_mcp: Optional[bool] = None,
) -> None:
    """Programmatic entry to the interactive ``opik configure`` flow.

    Reused by ``opik connect`` / ``opik endpoint`` so they can auto-launch
    configuration when no ~/.opik.config is present.
    """
    if use_local:
        opik_configure.configure(
            use_local=True,
            force=True,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
        )
        return

    deployment_type_choice = interactive_helpers.ask_user_for_deployment_type()

    if deployment_type_choice == interactive_helpers.DeploymentType.CLOUD:
        configurator = opik_configure.OpikConfigurator(
            url=opik_configure.OPIK_BASE_URL_CLOUD,
            use_local=False,
            force=True,
            self_hosted_comet=False,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
        )
    elif deployment_type_choice == interactive_helpers.DeploymentType.SELF_HOSTED:
        configurator = opik_configure.OpikConfigurator(
            use_local=False,
            force=True,
            self_hosted_comet=True,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
        )
    elif deployment_type_choice == interactive_helpers.DeploymentType.LOCAL:
        configurator = opik_configure.OpikConfigurator(
            use_local=True,
            force=True,
            self_hosted_comet=False,
            automatic_approvals=automatic_approvals,
            install_mcp=install_mcp,
        )
    else:
        raise click.ClickException("Unknown deployment type was selected. Exiting.")

    configurator.configure()


@click.command(context_settings={"ignore_unknown_options": True})
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
@click.option(
    "--install-mcp/--no-install-mcp",
    default=None,
    help="Register the Opik MCP server with detected AI hosts (Claude Code, Cursor, "
    "VS Code). When omitted, you are prompted interactively.",
)
def configure(use_local: bool, yes: bool, install_mcp: Optional[bool]) -> None:
    """
    Create a configuration file for the Opik Python SDK, if a configuration file already exists, it will be overwritten.
    This is also available as a function in the Python SDK.
    """
    run_interactive_configure(
        use_local=use_local, automatic_approvals=yes, install_mcp=install_mcp
    )
