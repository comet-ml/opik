"""Connect command - pairs and starts the runner in foreground."""

import logging
import platform

import click

LOGGER = logging.getLogger(__name__)


@click.command()
@click.option(
    "--pair",
    required=True,
    type=str,
    help="6-character pairing code from the Opik UI.",
)
@click.option(
    "--name",
    type=str,
    default=None,
    help="Runner name (defaults to hostname).",
)
@click.pass_context
def connect(ctx: click.Context, pair: str, name: str | None) -> None:
    """Pair with Opik and start the runner (foreground)."""
    from opik.runner import auth, config
    from opik.runner.runner import Runner

    runner_name = name or platform.node() or "local-runner"

    click.echo(f"Connecting runner '{runner_name}' with pairing code {pair}...")

    try:
        result = auth.connect_with_pairing_code(
            pairing_code=pair,
            runner_name=runner_name,
        )
    except Exception as e:
        raise click.ClickException(f"Failed to connect: {e}")

    runner_id = result["runner_id"]
    redis_url = result["redis_url"]
    workspace_id = result["workspace_id"]

    config.save_runner_config({
        "runner_id": runner_id,
        "redis_url": redis_url,
        "workspace_id": workspace_id,
        "runner_name": runner_name,
    })

    agents = config.load_agents()
    click.echo(f"Connected! Runner ID: {runner_id}")
    if agents:
        click.echo(f"Found {len(agents)} registered agent(s): {', '.join(agents.keys())}")
    else:
        click.echo("No agents registered yet. Run your @entrypoint script to register agents.")
    click.echo("Runner is running (Ctrl+C to stop)...")

    runner = Runner(runner_id=runner_id, redis_url=redis_url)
    runner.run()
