"""Eval app command for Opik CLI."""

import click


@click.command(name="eval-app")
@click.option(
    "--host",
    default="localhost",
    help="Host to bind to",
    show_default=True,
)
@click.option(
    "--port",
    default=8000,
    help="Port to bind to",
    show_default=True,
)
def eval_app(host: str, port: int) -> None:
    """Start the Opik evaluation app server.

    This command starts a FastAPI server that evaluates traces and logs
    feedback scores. Endpoints:
    - GET  /api/v1/evaluation/metrics - List available metrics
    - POST /api/v1/evaluation/traces  - Evaluate a trace
    - GET  /healthcheck               - Health check
    """
    try:
        import fastapi  # noqa
        import uvicorn  # noqa
    except ImportError:
        raise click.ClickException(
            "Eval app dependencies not found. Please install them with: pip install opik[eval-app]"
        )

    from opik.eval_app import create_app

    app = create_app()

    # Print startup message
    click.echo()
    click.echo(click.style("=" * 70, fg="cyan"))
    click.echo(click.style("  Opik Eval App", fg="cyan", bold=True))
    click.echo(click.style("=" * 70, fg="cyan"))
    click.echo()
    click.echo(
        f"  Server running at: {click.style(f'http://{host}:{port}', fg='green', bold=True)}"
    )
    click.echo()
    click.echo("  Available endpoints:")
    click.echo(
        f"    • GET  {click.style('/api/v1/evaluation/metrics', fg='yellow')} - List available metrics"
    )
    click.echo(
        f"    • POST {click.style('/api/v1/evaluation/traces', fg='yellow')}  - Evaluate trace & log scores"
    )
    click.echo(
        f"    • GET  {click.style('/healthcheck', fg='yellow')}               - Health check"
    )
    click.echo()
    click.echo("  Example request:")
    click.echo("    curl -X POST http://localhost:8000/api/v1/evaluation/traces \\")
    click.echo('      -H "Content-Type: application/json" \\')
    click.echo('      -d \'{"trace_id": "...", "metrics": [{"name": "Equals"}],')
    click.echo(
        '           "field_mapping": {"mapping": {"output": "output", "reference": "input"}}}\''
    )
    click.echo()
    click.echo(click.style("=" * 70, fg="cyan"))
    click.echo()

    import uvicorn

    uvicorn.run(app, host=host, port=port, log_level="info")
