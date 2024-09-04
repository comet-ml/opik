import click
import subprocess
import sys


@click.group()
def cli() -> None:
    """CLI tool for Opik."""
    pass


@cli.group()
def server() -> None:
    """Manage the Opik server."""
    pass


@server.command(
    context_settings=dict(
        ignore_unknown_options=True,
        allow_extra_args=True,
    )
)
def install() -> int:
    """Install the Opik server."""
    _ensure_opik_installer_is_installed()

    extra_arguments = sys.argv[4:]  # take everything after "opik server install"
    new_arguments = ["opik-server", "install"] + extra_arguments
    return subprocess.check_call(new_arguments)


@server.command(
    context_settings=dict(
        ignore_unknown_options=True,
        allow_extra_args=True,
    )
)
def upgrade() -> int:
    """Upgrade the Opik server."""
    _ensure_opik_installer_is_installed()
    extra_arguments = sys.argv[4:]  # take everything after "opik server install"
    new_arguments = ["opik-server", "upgrade"] + extra_arguments
    return subprocess.check_call(new_arguments)


def _ensure_opik_installer_is_installed() -> None:
    try:
        __import__("opik_installer")
    except ImportError:
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "opik-installer"]
        )


if __name__ == "__main__":
    cli()
