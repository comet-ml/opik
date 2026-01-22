"""Healthcheck command for Opik CLI."""

from typing import Optional

import click

import opik.healthcheck as opik_healthcheck
from opik.cli.healthcheck.smoke_test import run_smoke_test


@click.command(context_settings={"ignore_unknown_options": True})
@click.option(
    "--show-installed-packages",
    is_flag=True,
    default=False,
    help="Print the list of installed packages to the console.",
)
@click.option(
    "--smoke-test",
    is_flag=True,
    default=False,
    help="Run a smoke test to verify Opik integration is working correctly.",
)
@click.option(
    "--workspace",
    type=str,
    help="Workspace name to use for the smoke test (required when --smoke-test is used).",
)
@click.option(
    "--project-name",
    type=str,
    default="smoke-test-project",
    help="Project name for the smoke test. Defaults to 'smoke-test-project'.",
)
@click.pass_context
def healthcheck(
    ctx: click.Context,
    show_installed_packages: bool,
    smoke_test: bool,
    workspace: Optional[str],
    project_name: str,
) -> None:
    """
    Performs a health check of the application, including validation of configuration,
    verification of library installations, and checking the availability of the backend workspace.
    Prints all relevant information to assist in debugging and diagnostics.

    When --smoke-test is used, runs a smoke test to verify Opik integration is working correctly.
    The smoke test performs a basic sanity test by:
    - Creating a trace with random data
    - Adding a tracked LLM completion span
    - Attaching a dynamically generated test image
    - Verifying data is sent to Opik

    Examples:

      Run standard healthcheck:

        opik healthcheck

      Run healthcheck with smoke test:

        opik healthcheck --smoke-test --workspace my-workspace

      Run smoke test with custom project name:

        opik healthcheck --smoke-test --workspace my-workspace --project-name my-test-project
    """
    if smoke_test:
        if not workspace:
            raise click.ClickException(
                "--workspace is required when --smoke-test is used"
            )
        # Get API key and debug flag from context
        api_key = ctx.obj.get("api_key") if ctx.obj else None
        debug = ctx.obj.get("debug", False) if ctx.obj else False

        run_smoke_test(
            workspace=workspace,
            project_name=project_name,
            api_key=api_key,
            debug=debug,
        )
    else:
        opik_healthcheck.run(show_installed_packages)
