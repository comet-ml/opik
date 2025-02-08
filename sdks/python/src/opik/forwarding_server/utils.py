"""Utilities for the Opik forwarding server."""

from rich.console import Console
from rich.panel import Panel
from rich.text import Text
from rich import box
from rich.align import Align
import httpx

console = Console()


def check_llm_server_running(url: str) -> bool:
    """Check if the LLM server is accessible."""
    try:
        with httpx.Client() as client:
            client.get(f"{url}", timeout=2.0)
            return True
    except Exception:
        return False


def print_server_startup_message(
    host: str,
    port: int,
    llm_server_type: str,
    llm_server_host: str,
) -> None:
    """Print a nicely formatted server startup message."""
    # Create main content with improved styling
    local_url = f"http://{host if host != '0.0.0.0' else '127.0.0.1'}:{port}"

    # Status section with improved formatting
    content = Text()
    if not check_llm_server_running(llm_server_host):
        content.append("\n")
        content.append(
            f"⚠️ The {llm_server_type} server is not running at {llm_server_host}, all LLM calls will fail.\n",
            style="bold red",
        )

        content.append(
            "\n───────────────────────────────────────────────────────────────────────────────────────────────\n"
        )

    content.append("\n")
    content.append(
        "The Opik proxy server is now running, you can now navigate to Opik and setup up\n"
    )
    content.append("your new AI provider!\n\n")
    content.append("🚀 Proxy server running at:\n")
    content.append(f"   - {local_url}\n")
    content.append("\n")
    content.append("📚 Documentation:\n")
    content.append("   - https://www.comet.com/docs/opik/playground\n")
    content.append("\n")
    content.append("Note:", style="bold yellow")
    content.append(
        "\n   This is a simple proxy server that allows you to use local LLMs with the Opik\n"
    )
    content.append("   playground. It is not meant for production use.\n")

    # Create the main panel with rounded corners and title
    main_panel = Panel(
        Align.left(content),
        box=box.ROUNDED,
        border_style="cyan",
        padding=(0, 2),
        title="Opik Proxy Server",
        title_align="center",
    )

    # Print everything with proper spacing
    console.print()
    console.print(main_panel)
    console.print()
