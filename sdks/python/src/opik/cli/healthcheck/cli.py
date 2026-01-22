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
    type=str,
    default=None,
    help="Run a smoke test to verify Opik integration is working correctly. Requires WORKSPACE value.",
    metavar="WORKSPACE",
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
    smoke_test: Optional[str],
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

        opik healthcheck --smoke-test my-workspace

      Run smoke test with custom project name:

        opik healthcheck --smoke-test my-workspace --project-name my-test-project
    """
    if smoke_test is not None:
        # Validate that workspace is not empty
        if smoke_test == "":
            raise click.BadParameter(
                "--smoke-test requires a non-empty workspace name",
                param_hint="--smoke-test",
            )

        # Get API key and debug flag from context
        api_key = ctx.obj.get("api_key") if ctx.obj else None
        debug = ctx.obj.get("debug", False) if ctx.obj else False

        run_smoke_test(
            workspace=smoke_test,
            project_name=project_name,
            api_key=api_key,
            debug=debug,
        )
    else:
        opik_healthcheck.run(show_installed_packages)
