"""CLI command to run the eval app server."""

import os
import signal
import sys
from pathlib import Path
from typing import Optional

import click

# PID file location
PID_FILE = Path.home() / ".opik" / "eval_app.pid"


def _get_pid() -> Optional[int]:
    """Get the PID from the PID file if it exists."""
    if PID_FILE.exists():
        try:
            return int(PID_FILE.read_text().strip())
        except (ValueError, OSError):
            return None
    return None


def _write_pid(pid: int) -> None:
    """Write the current PID to the PID file."""
    PID_FILE.parent.mkdir(parents=True, exist_ok=True)
    PID_FILE.write_text(str(pid))


def _remove_pid() -> None:
    """Remove the PID file."""
    if PID_FILE.exists():
        PID_FILE.unlink()


def _is_process_running(pid: int) -> bool:
    """Check if a process with the given PID is running."""
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


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
        content.append(f"   - POST {local_url}/api/v1/evaluation/traces/{{trace_id}}\n")
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
    # Check if already running
    existing_pid = _get_pid()
    if existing_pid and _is_process_running(existing_pid):
        click.echo(f"Eval App is already running (PID: {existing_pid})")
        click.echo("Use 'opik eval-app stop' to stop it first, or 'opik eval-app restart'")
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
    _write_pid(os.getpid())

    _print_startup_message(host, port, metrics_count)

    try:
        uvicorn.run(
            app,
            host=host,
            port=port,
            reload=reload,
            log_level="error",  # Reduce uvicorn logging to keep output clean
        )
    finally:
        _remove_pid()


@eval_app.command()
def stop() -> None:
    """Stop the running Eval App server."""
    pid = _get_pid()

    if pid is None:
        click.echo("No Eval App server is running (no PID file found)")
        return

    if not _is_process_running(pid):
        click.echo(f"Eval App server (PID: {pid}) is not running")
        _remove_pid()
        return

    click.echo(f"Stopping Eval App server (PID: {pid})...")

    try:
        os.kill(pid, signal.SIGTERM)
        click.echo("Server stopped successfully")
    except OSError as e:
        click.echo(f"Failed to stop server: {e}", err=True)
        raise click.Abort()
    finally:
        _remove_pid()


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
    pid = _get_pid()

    if pid and _is_process_running(pid):
        click.echo(f"Stopping existing server (PID: {pid})...")
        try:
            os.kill(pid, signal.SIGTERM)
            # Wait a moment for the process to terminate
            import time
            time.sleep(1)
        except OSError:
            pass
        _remove_pid()

    click.echo("Starting server...")
    ctx.invoke(start, host=host, port=port, reload=reload)


@eval_app.command()
def status() -> None:
    """Check if the Eval App server is running."""
    pid = _get_pid()

    if pid is None:
        click.echo("Eval App server is not running (no PID file)")
        sys.exit(1)

    if _is_process_running(pid):
        click.echo(f"Eval App server is running (PID: {pid})")
        sys.exit(0)
    else:
        click.echo(f"Eval App server is not running (stale PID: {pid})")
        _remove_pid()
        sys.exit(1)
