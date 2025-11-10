"""Healthcheck command for Opik CLI."""

import click

import opik.healthcheck as opik_healthcheck


@click.command(context_settings={"ignore_unknown_options": True})
@click.option(
    "--show-installed-packages",
    is_flag=True,
    default=False,
    help="Print the list of installed packages to the console.",
)
def healthcheck(show_installed_packages: bool) -> None:
    """
    Performs a health check of the application, including validation of configuration,
    verification of library installations, and checking the availability of the backend workspace.
    Prints all relevant information to assist in debugging and diagnostics.
    """
    opik_healthcheck.run(show_installed_packages)
