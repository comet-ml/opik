"""Display utilities for the eval app CLI."""

import click


def print_startup_message(host: str, port: int, metrics_count: int) -> None:
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

