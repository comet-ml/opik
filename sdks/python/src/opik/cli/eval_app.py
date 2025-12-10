"""CLI command to run the eval app server."""

import socket

import click

import os


def _is_port_in_use(host: str, port: int) -> bool:
    """Check if a port is already in use."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        result = sock.connect_ex((host, port))
        return result == 0


def _print_startup_message(
    host: str,
    port: int,
    metrics_count: int,
    workers: int,
    metric_threads: int,
) -> None:
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
        content.append(f"   - Workers: {workers}\n")
        content.append(f"   - Metric threads per worker: {metric_threads}\n")
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
        click.echo(f"Workers: {workers}, Metric threads: {metric_threads}")
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
    "--workers",
    default=8,
    type=int,
    help="Number of worker processes",
    show_default=True,
)
@click.option(
    "--metric-threads",
    default=8,
    type=int,
    help="Number of threads per worker for parallel metric execution",
    show_default=True,
)
@click.option(
    "--reload",
    is_flag=True,
    default=False,
    help="Enable auto-reload on code changes (only works with 1 worker)",
)
def eval_app(
    host: str, port: int, workers: int, metric_threads: int, reload: bool
) -> None:
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
    from opik.eval_app.eval_service import metrics

    # Get metrics count for display
    registry = metrics.get_default_registry()
    metrics_count = len(registry.list_all())

    # Check if port is already in use before starting
    if _is_port_in_use(host, port):
        click.echo(
            f"\nError: Port {port} is already in use.\n",
            err=True,
        )
        click.echo(
            "To kill the existing process, run:",
            err=True,
        )
        click.echo(
            f"  lsof -ti:{port} | xargs kill -9\n",
            err=True,
        )
        raise click.Abort()

    # Validate options
    if reload and workers > 1:
        click.echo(
            "Warning: --reload only works with 1 worker. Using 1 worker.",
            err=True,
        )
        workers = 1

    # Set environment variable for metric threads (used by workers)
    os.environ["OPIK_EVAL_APP_METRIC_THREADS"] = str(metric_threads)

    _print_startup_message(host, port, metrics_count, workers, metric_threads)

    # Use app string for multiple workers, app object for single worker with reload
    if workers > 1:
        uvicorn.run(
            "opik.eval_app.entrypoint:create_app",
            factory=True,
            host=host,
            port=port,
            workers=workers,
            log_level="error",
        )
    else:
        from opik.eval_app import entrypoint

        app = entrypoint.create_app()
        uvicorn.run(
            app,
            host=host,
            port=port,
            reload=reload,
            log_level="error",
        )
