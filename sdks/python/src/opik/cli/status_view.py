"""Terminal rendering for the ``opik`` status commands.

Covers ``opik configure status`` and ``opik mcp status``. Presentation only — no
business logic lives here. Command handlers gather the data (an ``OpikConfig``,
the host statuses computed by ``configurator.mcp.status``) and hand it to these
functions to be displayed.
"""

import pathlib
from typing import Iterable, List, Optional, Tuple

import rich.console
from rich import padding, table, text

import opik.config as opik_config
from opik.configurator.mcp import doctor as mcp_doctor
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
    config: opik_config.OpikConfig,
    host_statuses: List[mcp_status.HostStatus],
    tool_note: Optional[str] = None,
) -> None:
    """Print the Opik config summary plus each AI client that has the MCP server.

    Assistants without an Opik MCP registration are omitted. ``tool_note``, when
    given, is the advisory about an ``opik-mcp`` installed as a uv tool; it is
    printed last because it describes the machine rather than any one client.
    """
    render_config_summary(config)
    console.print()

    configured = [host for host in host_statuses if host.registered]
    if not configured:
        console.print("The Opik MCP server is not configured for any AI client.")
        console.print("Run [bold]opik mcp configure[/bold] to set it up.")
        return

    count = len(configured)
    noun = "client" if count == 1 else "clients"
    console.print(
        text.Text(f"Opik MCP server — configured for {count} AI {noun}:", style="bold")
    )

    for host in configured:
        _render_host(host)

    if tool_note:
        console.print()
        console.print(text.Text(tool_note, style="yellow"))


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


def render_mcp_doctor(diagnosis: mcp_doctor.Diagnosis) -> None:
    """Print what each client actually launches, and whether it is the current release.

    Ordered so the verdict lands last per client: the reader wants an answer, and
    the evidence above it is what makes the answer checkable.
    """
    if len(diagnosis.hosts) == 0:
        console.print("The Opik MCP server is not configured for any AI client.")
        console.print("Run [bold]opik mcp configure[/bold] to set it up.")
        return

    count = len(diagnosis.hosts)
    noun = "client" if count == 1 else "clients"
    console.print(
        text.Text(f"Opik MCP server — {count} AI {noun} configured:", style="bold")
    )

    for host in diagnosis.hosts:
        console.print()
        console.print(
            padding.Padding(
                text.Text(host.display_name, style="bold"), (0, 0, 0, 2), expand=False
            )
        )
        console.print(_render_fields(_doctor_rows(host, diagnosis.latest_version)))

    if diagnosis.uv_tool_version is not None:
        console.print()
        console.print(text.Text("Also on this machine", style="bold"))
        console.print(
            _render_fields(
                [
                    (
                        "uv tool",
                        text.Text(
                            f"opik-mcp {diagnosis.uv_tool_version} is installed as a uv "
                            f"tool. `uv tool upgrade opik-mcp` updates it; `uv tool "
                            f"uninstall opik-mcp` removes it.",
                        ),
                    )
                ]
            )
        )


def _doctor_rows(
    host: mcp_doctor.HostDiagnosis, latest: Optional[str]
) -> List[Tuple[str, text.Text]]:
    rows: List[Tuple[str, text.Text]] = [
        ("Config", text.Text(_display_path(host.config_path), style="dim"))
    ]

    if host.hosted_url:
        rows.append(("Connection", text.Text("Hosted (HTTP + OAuth)")))
        rows.append(("Reports to", text.Text(host.hosted_url)))
        rows.append(
            (
                "Version",
                text.Text(
                    "always current — no local package is involved", style="green"
                ),
            )
        )
        return rows

    rows.append(("Connection", text.Text("Local (uvx)")))
    if host.launches:
        rows.append(("Launches", text.Text(host.launches, style="dim")))

    if host.problem is not None:
        rows.append(("Verdict", text.Text(f"✗ {host.problem}", style="red")))
        return rows

    source = (
        "from `uv tool install` — pinned"
        if host.source == mcp_doctor.SOURCE_TOOL_INSTALL
        else "resolved from the package index"
    )
    rows.append(("Running", text.Text(f"opik-mcp {host.running_version} ({source})")))

    if latest is None:
        rows.append(
            (
                "Verdict",
                text.Text(
                    "? could not reach the package index to compare", style="yellow"
                ),
            )
        )
        return rows

    rows.append(("Published", text.Text(f"opik-mcp {latest}")))
    if host.running_version == latest:
        rows.append(
            ("Verdict", text.Text("✓ running the published version", style="green"))
        )
    else:
        rows.append(
            (
                "Verdict",
                text.Text(
                    f"✗ STALE — re-run `opik mcp configure`, then start a new "
                    f"session in {host.display_name}",
                    style="red",
                ),
            )
        )
    return rows
