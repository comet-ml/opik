"""Terminal rendering for ``opik configure status`` and ``opik mcp status``.

Presentation only — no business logic lives here. Command handlers gather the
data (an ``OpikConfig`` and, for MCP, the host statuses computed by
``configurator.mcp.status``) and hand it to these functions to be displayed.
"""

import pathlib
from typing import Iterable, List, Tuple

import rich.console
from rich import padding, table, text

import opik.config as opik_config
from opik.configurator.mcp import status as mcp_status

console = rich.console.Console()

_KEY_STYLE = "cyan"
_FIELDS_INDENT = (0, 0, 0, 4)


def _display_path(path: pathlib.Path) -> str:
    """Render a path with the user's home directory collapsed to ``~``."""
    home = str(pathlib.Path.home())
    text_value = str(path)
    return f"~{text_value[len(home) :]}" if text_value.startswith(home) else text_value


def _render_fields(rows: Iterable[Tuple[str, text.Text]]) -> padding.Padding:
    """Build an indented two-column label/value grid."""
    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(style=_KEY_STYLE, no_wrap=True)
    grid.add_column(overflow="fold")
    for label, value in rows:
        grid.add_row(label, value)
    return padding.Padding(grid, _FIELDS_INDENT, expand=False)


def render_config_summary(config: opik_config.OpikConfig) -> None:
    """Print the active Opik configuration: file path, environment, workspace."""
    console.print(text.Text("Your Opik configuration", style="bold"))
    if config.config_file_exists:
        rows: List[Tuple[str, text.Text]] = [
            (
                "File",
                text.Text(_display_path(config.config_file_fullpath), style="dim"),
            ),
            ("Environment", text.Text(config.url_override)),
            ("Workspace", text.Text(config.workspace or "-")),
        ]
    else:
        rows = [
            (
                "File",
                text.Text.assemble(
                    _display_path(config.config_file_fullpath) + " ",
                    ("(not found — run `opik configure`)", "yellow"),
                ),
            )
        ]
    console.print(_render_fields(rows))


def render_mcp_status(
    config: opik_config.OpikConfig, host_statuses: List[mcp_status.HostStatus]
) -> None:
    """Print the Opik config summary plus each assistant that has the MCP server.

    Assistants without an Opik MCP registration are omitted.
    """
    render_config_summary(config)
    console.print()

    configured = [host for host in host_statuses if host.registered]
    if not configured:
        console.print("The Opik MCP server is not configured for any AI assistant.")
        console.print("Run [bold]opik mcp configure[/bold] to set it up.")
        return

    count = len(configured)
    noun = "assistant" if count == 1 else "assistants"
    console.print(
        text.Text(f"Opik MCP server — configured for {count} AI {noun}:", style="bold")
    )

    for host in configured:
        _render_host(host)


def _render_host(host: mcp_status.HostStatus) -> None:
    console.print()
    console.print(
        padding.Padding(
            text.Text(host.display_name, style="bold"), (0, 0, 0, 2), expand=False
        )
    )

    rows: List[Tuple[str, text.Text]] = [
        ("Config", text.Text(_display_path(host.config_path), style="dim")),
        ("Connection", text.Text(host.transport or "-")),
        ("Reports to", text.Text(host.points_to or "-")),
    ]
    if host.workspace is not None:
        rows.append(("Workspace", text.Text(host.workspace)))
    if host.in_sync:
        rows.append(
            (
                "Status",
                text.Text("✓ in sync with your Opik configuration", style="green"),
            )
        )
    else:
        rows.append(
            (
                "Status",
                text.Text(
                    "✗ OUT OF SYNC with your Opik configuration — "
                    "run `opik mcp configure` to re-sync",
                    style="red",
                ),
            )
        )
    console.print(_render_fields(rows))
