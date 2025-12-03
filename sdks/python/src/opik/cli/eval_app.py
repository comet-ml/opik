"""CLI command to run the eval app server."""

import click


@click.command(
    context_settings={"ignore_unknown_options": True},
    help="""
Start the Opik Eval App server.

This server provides API endpoints to:
- GET  /api/v1/evaluation/metrics - List available metrics
- POST /api/v1/evaluation/traces/{trace_id} - Evaluate a trace and log feedback scores
- GET  /healthcheck - Health check

The server connects to your local Opik instance to fetch traces and log feedback scores.
""",
)
@click.option(
    "--host",
    default="127.0.0.1",
    help="Host to bind the server to",
    show_default=True,
)
@click.option(
    "--port",
    default=5001,
    type=int,
    help="Port to bind the server to",
    show_default=True,
)
@click.option(
    "--reload",
    is_flag=True,
    default=False,
    help="Enable auto-reload on code changes",
)
def eval_app(host: str, port: int, reload: bool) -> None:
    """Start the Opik Eval App server."""
    try:
        import uvicorn
    except ImportError:
        click.echo(
            "Error: uvicorn is required. Install with: pip install opik[eval-app]",
            err=True,
        )
        raise click.Abort()

    try:
        import fastapi  # noqa: F401
    except ImportError:
        click.echo(
            "Error: fastapi is required. Install with: pip install opik[eval-app]",
            err=True,
        )
        raise click.Abort()

    # Import and create the app
    from opik import eval_app as eval_app_module

    app = eval_app_module.create_app()

    click.echo(f"Starting Opik Eval App server at http://{host}:{port}")
    click.echo("Available endpoints:")
    click.echo(f"  GET  http://{host}:{port}/api/v1/evaluation/metrics")
    click.echo(f"  POST http://{host}:{port}/api/v1/evaluation/traces/{{trace_id}}")
    click.echo(f"  GET  http://{host}:{port}/healthcheck")
    click.echo("")

    uvicorn.run(
        app,
        host=host,
        port=port,
        reload=reload,
    )

