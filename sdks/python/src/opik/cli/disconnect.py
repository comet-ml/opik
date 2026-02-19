"""Disconnect command - stops the local runner daemon."""

import click


@click.command()
def disconnect() -> None:
    """Stop the local runner daemon."""
    try:
        from opik.runner import daemon, config
    except ImportError:
        raise click.ClickException(
            "Runner dependencies not found. Install with: pip install opik[runner]"
        )

    pid = daemon.get_daemon_pid()
    if pid is None:
        click.echo("No runner daemon is running.")
        return

    if daemon.stop_daemon():
        click.echo(f"Runner daemon (PID: {pid}) stopped.")
    else:
        click.echo("Runner daemon was not running.")

    config.clear_runner_config()
