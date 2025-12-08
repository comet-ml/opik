"""CLI command to run the eval app server."""

import click


def _print_startup_message(host: str, port: int, metrics_count: int) -> None:
    """Print a nicely formatted server startup message."""
    try:
        from rich.console import Console
        from rich.panel import Panel
        from rich.text import Text
        from rich import box
        from rich.align import Align

        console = Console()
        local_url = f"http://{host if host != '0.0.0.0' else '127.0.0.1'}:{port}"

        content = Text()
        content.append("\n")
        content.append("🚀 Server running:\n")
        content.append(f"   - URL: {local_url}\n")
        content.append(f"   - Metrics available: {metrics_count}\n")
        content.append("\n")
        content.append("📡 API Endpoints:\n")
        content.append(f"   - GET  {local_url}/api/v1/evaluation/metrics\n")
        content.append(f"   - POST {local_url}/api/v1/evaluation/trace\n")
        content.append(f"   - GET  {local_url}/healthcheck\n")
        content.append("\n")
        content.append("📚 Documentation:\n")
        content.append(
            "   - https://www.comet.com/docs/opik/evaluation/metrics/overview\n"
        )
        content.append("\n")
        content.append("Note:", style="bold yellow")
        content.append(
            "\n   This server is meant for local development. Configure it in the\n"
            "   Opik Playground to run Python metrics on your traces.\n"
        )

        main_panel = Panel(
            Align.left(content),
            box=box.ROUNDED,
            border_style="cyan",
            padding=(0, 2),
            title="Opik Eval App",
            title_align="center",
        )

        console.print()
        console.print(main_panel)
        console.print()
    except ImportError:
        # Fallback if rich is not installed
        click.echo(f"\nOpik Eval App running at http://{host}:{port}")
        click.echo(f"Metrics available: {metrics_count}")
        click.echo("")


@click.command(
    help="""
Start the Opik Eval App server.

This server provides API endpoints to:
- GET  /api/v1/evaluation/metrics - List available metrics
- POST /api/v1/evaluation/trace - Evaluate a trace and log feedback scores
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
    from opik.eval_app.services import metrics

    app = eval_app_module.create_app()

    # Get metrics count for display
    registry = metrics.get_default_registry()
    metrics_count = len(registry.list_all())

    _print_startup_message(host, port, metrics_count)

    uvicorn.run(
        app,
        host=host,
        port=port,
        reload=reload,
        log_level="error",
    )
