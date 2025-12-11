"""
Harbor CLI integration with Opik tracking.

Usage:
    opik harbor run -d terminal-bench@head -a terminus_2 -m gpt-4.1
    opik harbor jobs start -c config.yaml
"""

import sys

import click


@click.command(
    name="harbor",
    context_settings={
        "ignore_unknown_options": True,
        "allow_extra_args": True,
        "allow_interspersed_args": False,
    },
)
@click.pass_context
def harbor(ctx: click.Context) -> None:
    """Run Harbor benchmarks with Opik tracking enabled."""
    try:
        import harbor  # noqa: F401
    except ImportError:
        raise click.ClickException(
            "Harbor is not installed. Install with: pip install harbor"
        )

    from opik.integrations.harbor import track_harbor

    track_harbor()

    from harbor.cli.main import app

    sys.argv = ["harbor"] + ctx.args
    app()
