"""CLI commands for the eval app."""

import os
import sys

import click

from . import display
from . import process_manager


@click.group(
    invoke_without_command=True,
    help="""
Manage the Opik Eval App server.

Run 'opik eval-app start' to start the server, 'opik eval-app stop' to stop it.

The server provides API endpoints to:
- GET  /api/v1/evaluation/metrics - List available metrics
- POST /api/v1/evaluation/traces/{trace_id} - Evaluate a trace and log feedback scores
- GET  /healthcheck - Health check
""",
)
@click.pass_context
def eval_app(ctx: click.Context) -> None:
    """Manage the Opik Eval App server."""
    # If no subcommand provided, default to 'start'
    if ctx.invoked_subcommand is None:
        ctx.invoke(start)


@eval_app.command()
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
def start(host: str, port: int, reload: bool) -> None:
    """Start the Opik Eval App server."""
    is_running, existing_pid = process_manager.get_server_status()
    if is_running:
        click.echo(f"Eval App is already running (PID: {existing_pid})")
        click.echo(
            "Use 'opik eval-app stop' to stop it first, or 'opik eval-app restart'"
        )
        raise click.Abort()

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
    from opik.eval_app.services.metrics import get_default_registry

    app = eval_app_module.create_app()

    # Get metrics count for display
    registry = get_default_registry()
    metrics_count = len(registry.list_all())

    # Write PID file
    process_manager.write_pid(os.getpid())

    display.print_startup_message(host, port, metrics_count)

    try:
        uvicorn.run(
            app,
            host=host,
            port=port,
            reload=reload,
            log_level="error",  # Reduce uvicorn logging to keep output clean
        )
    finally:
        process_manager.remove_pid()


@eval_app.command()
def stop() -> None:
    """Stop the running Eval App server."""
    is_running, pid = process_manager.get_server_status()

    if pid is None:
        click.echo("No Eval App server is running (no PID file found)")
        return

    if not is_running:
        click.echo(f"Eval App server (PID: {pid}) is not running")
        process_manager.remove_pid()
        return

    click.echo(f"Stopping Eval App server (PID: {pid})...")

    if process_manager.stop_process(pid):
        click.echo("Server stopped successfully")
        process_manager.remove_pid()
    else:
        click.echo("Failed to stop server", err=True)
        raise click.Abort()


@eval_app.command()
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
@click.pass_context
def restart(ctx: click.Context, host: str, port: int, reload: bool) -> None:
    """Restart the Eval App server."""
    is_running, pid = process_manager.get_server_status()

    if is_running and pid is not None:
        click.echo(f"Stopping existing server (PID: {pid})...")
        process_manager.stop_process(pid)
        process_manager.remove_pid()

    click.echo("Starting server...")
    ctx.invoke(start, host=host, port=port, reload=reload)


@eval_app.command()
def status() -> None:
    """Check if the Eval App server is running."""
    is_running, pid = process_manager.get_server_status()

    if pid is None:
        click.echo("Eval App server is not running (no PID file)")
        sys.exit(1)

    if is_running:
        click.echo(f"Eval App server is running (PID: {pid})")
        sys.exit(0)
    else:
        click.echo(f"Eval App server is not running (stale PID: {pid})")
        process_manager.remove_pid()
        sys.exit(1)

