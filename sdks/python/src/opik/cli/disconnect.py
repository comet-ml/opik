import errno
import logging

import click

from opik.runner import state

LOGGER = logging.getLogger(__name__)


@click.command()
def disconnect() -> None:
    """Disconnect the local runner."""
    runner_state = state.RunnerState.load()
    if runner_state is None:
        click.echo("No runner is currently connected.")
        return

    try:
        state.send_shutdown_signal(runner_state.pid)
        click.echo(f"Shutdown signal sent to runner (PID {runner_state.pid}).")
        state.RunnerState.clear()
        click.echo("Runner state cleared.")
    except OSError as e:
        if e.errno == errno.ESRCH:
            click.echo(f"Runner process (PID {runner_state.pid}) is no longer running.")
            state.RunnerState.clear()
            click.echo("Runner state cleared.")
        else:
            click.echo(f"Could not signal runner (PID {runner_state.pid}): {e}")
