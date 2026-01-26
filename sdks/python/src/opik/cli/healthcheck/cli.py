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
    default=None,
    help="Project name for the smoke test (only used when --smoke-test is provided). Defaults to 'smoke-test-project'.",
)
@click.pass_context
def healthcheck(
    ctx: click.Context,
    show_installed_packages: bool,
    smoke_test: Optional[str],
    project_name: Optional[str],
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
    # Validate that --project-name is only used with --smoke-test
    if project_name is not None and smoke_test is None:
        raise click.BadParameter(
            "--project-name can only be used with --smoke-test",
            param_hint="--project-name",
        )

    if smoke_test is not None:
        # Use default project name if smoke_test is provided but project_name is not
        if project_name is None:
            project_name = "smoke-test-project"

        # Validate that workspace is not empty
        if smoke_test == "":
            raise click.BadParameter(
                "--smoke-test requires a non-empty workspace name",
                param_hint="--smoke-test",
            )

        # Get API key and debug flag from context
        api_key = ctx.obj.get("api_key") if ctx.obj else None
        debug = ctx.obj.get("debug", False) if ctx.obj else False

        try:
            run_smoke_test(
                workspace=smoke_test,
                project_name=project_name,
                api_key=api_key,
                debug=debug,
            )
        except Exception as e:
            click.echo(f"Smoke test failed: {e}", err=True)
            if debug:
                import traceback

                click.echo("Traceback:", err=True)
                click.echo(traceback.format_exc(), err=True)

    # Always run the standard healthcheck
    opik_healthcheck.run(show_installed_packages)
