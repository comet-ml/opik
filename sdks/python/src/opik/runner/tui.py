"""TUI — inline display with Rich Live pending panel for bridge ops."""

import threading
import time
from dataclasses import dataclass
from typing import Optional

from rich.console import Console, ConsoleOptions, RenderResult
from rich.live import Live
from rich.style import Style
from rich.text import Text

_R_START, _G_START, _B_START = 0xF5, 0xA6, 0x23
_R_END, _G_END, _B_END = 0xE0, 0x3E, 0x2D
_CYCLE_LENGTH = 20


def _color_for_line(n: int) -> str:
    t = (n % (2 * _CYCLE_LENGTH)) / _CYCLE_LENGTH
    if t > 1:
        t = 2 - t
    r = int(_R_START + (_R_END - _R_START) * t)
    g = int(_G_START + (_G_END - _G_START) * t)
    b = int(_B_START + (_B_END - _B_START) * t)
    return f"rgb({r},{g},{b})"


@dataclass
class _OpEntry:
    command_id: str
    command_type: str
    summary: str


class _LivePanel:
    """Renderable wrapper that re-queries TUI state on every refresh cycle."""

    def __init__(self, tui: "RunnerTUI") -> None:
        self._tui = tui

    def __rich_console__(
        self, console: Console, options: ConsoleOptions
    ) -> RenderResult:
        yield self._tui._build_panel()


class RunnerTUI:
    _PADDING = " " * 11
    _LABEL_WIDTH = 13
    _OPS_SEPARATOR = "\u2576" + "\u2500" * 46 + "\u2574"

    def __init__(self, console: Optional[Console] = None) -> None:
        self._console = console or Console()
        self._is_tty = self._console.is_terminal
        self._pending_ops: dict[str, _OpEntry] = {}
        self._line_count = 0
        self._live: Optional[Live] = None
        self._lock = threading.Lock()
        self._pairing_active = False
        self._pairing_deadline: Optional[float] = None
        self._pairing_url: Optional[str] = None
        self._project_url: Optional[str] = None

    def start(self) -> None:
        if self._is_tty:
            self._live = Live(
                _LivePanel(self),
                console=self._console,
                refresh_per_second=2,
                transient=False,
            )
            self._live.start()

    def stop(self) -> None:
        if self._live is not None:
            self._live.stop()
            self._live = None

    def print_banner(
        self,
        project_name: str,
        url: str = "",
    ) -> None:
        padding = self._PADDING
        lw = self._LABEL_WIDTH

        info = Text()
        info.append("   ")
        info.append("\u2800\u20dd", style="rgb(224,62,45)")
        info.append(" opik  ", style="bold")
        info.append("Opik URL".ljust(lw), style="dim")
        info.append(url if url else "-")
        info.append(f"\n{padding}")
        info.append("Project".ljust(lw), style="dim")
        info.append(project_name)

        self._console.print(info)

    def pairing_started(self, url: str, timeout_seconds: int) -> None:
        with self._lock:
            self._pairing_active = True
            self._pairing_deadline = time.monotonic() + timeout_seconds
            self._pairing_url = url

        if not self._is_tty:
            self._console.print(f"Open this link to pair:\n{url}", soft_wrap=True)

    def pairing_completed(self, project_url: Optional[str] = None) -> None:
        with self._lock:
            was_active = self._pairing_active
            self._pairing_active = False
            self._pairing_deadline = None
            self._pairing_url = None
            self._project_url = project_url
        if not was_active:
            return

        text = Text()
        text.append(self._PADDING)
        text.append("Status".ljust(self._LABEL_WIDTH), style="dim")
        text.append("Paired ", style="green")
        text.append("\u2714", style="green")
        if project_url:
            text.append(f"\n\n{self._PADDING}")
            msg = "Continue developing in Opik:  "
            r_s, g_s, b_s = 91, 74, 228
            r_e, g_e, b_e = 170, 140, 255
            for i, ch in enumerate(msg):
                t = i / max(len(msg) - 1, 1)
                r = int(r_s + (r_e - r_s) * t)
                g = int(g_s + (g_e - g_s) * t)
                b = int(b_s + (b_e - b_s) * t)
                text.append(ch, style=f"rgb({r},{g},{b})")
            text.append("\u2197\ufe0f ", style=f"rgb({r_e},{g_e},{b_e})")
            text.append(
                "Link", style=Style(link=project_url, bold=True, underline=True)
            )
            text.append(f"\n{self._PADDING}")
            text.append(project_url, style="dim")
        self._print(text)
        self._update_live()

    def pairing_failed(self, reason: Optional[str] = None) -> None:
        with self._lock:
            was_active = self._pairing_active
            self._pairing_active = False
            self._pairing_deadline = None
            self._pairing_url = None
        if not was_active:
            return

        text = Text()
        text.append(self._PADDING)
        text.append("Status".ljust(self._LABEL_WIDTH), style="dim")
        text.append("Pairing failed ", style="red")
        text.append("\u2717", style="red")
        if reason:
            text.append(f" {reason}", style="dim red")
        self._print(text)
        self._console.print()
        self._update_live()

    def app_line(self, stream: str, line: str) -> None:
        color = _color_for_line(self._line_count)
        self._line_count += 1
        text = Text()
        text.append(" \u2503  ", style=color)
        text.append(line)
        self._print(text)

    def op_start(self, command_id: str, command_type: str, summary: str) -> None:
        entry = _OpEntry(
            command_id=command_id, command_type=command_type, summary=summary
        )
        with self._lock:
            self._pending_ops[command_id] = entry

        if not self._is_tty:
            return

        self._update_live()

    def op_end(
        self, command_id: str, success: bool, error: Optional[str] = None
    ) -> None:
        with self._lock:
            entry = self._pending_ops.pop(command_id, None)

        if entry is None:
            return

        text = Text()
        if success:
            text.append(" \u25cf ", style="green")
            text.append(entry.summary)
            text.append(" \u2713", style="green")
        else:
            text.append(" \u25cf ", style="red")
            text.append(entry.summary)
            text.append(" \u2717", style="red")
            if error:
                text.append(f" {error}", style="dim red")

        self._print(text)
        self._update_live()

    def child_restarted(self, reason: str) -> None:
        with self._lock:
            self._pending_ops.clear()

        text = Text()
        text.append(" \u2503  ", style="rgb(80,85,245)")
        text.append(f"Restarting: {reason}", style="rgb(80,85,245)")
        self._print(text)

        self._update_live()

    def error(self, message: str) -> None:
        text = Text()
        text.append(" \u25cf ", style="red")
        text.append(message, style="red")
        self._print(text)

    def _print(self, renderable: Text) -> None:
        if self._live is not None:
            self._live.console.print(renderable)
        else:
            self._console.print(renderable)

    def _build_panel(self) -> Text:
        with self._lock:
            pairing_active = self._pairing_active
            pairing_deadline = self._pairing_deadline
            pairing_url = self._pairing_url
            has_ops = bool(self._pending_ops)
            ops_snapshot = list(self._pending_ops.values()) if has_ops else []

        lines = Text()
        padding = self._PADDING
        lw = self._LABEL_WIDTH

        if pairing_active and pairing_deadline is not None and pairing_url is not None:
            remaining = max(0, int(pairing_deadline - time.monotonic()))
            mins, secs = divmod(remaining, 60)
            dot_on = int(time.monotonic() * 2) % 2 == 0
            dot_char = "\u25cf" if dot_on else " "

            lines.append(padding)
            lines.append("Status".ljust(lw), style="dim")
            lines.append("Pairing... ", style="yellow")
            lines.append(dot_char, style="yellow")
            lines.append(f" (timeout in {mins}m {secs:02d}s)", style="dim")
            lines.append(f"\n\n{padding}")
            lines.append("Open this link to pair:  \U0001f517 ")
            lines.append(
                "Link", style=Style(link=pairing_url, bold=True, underline=True)
            )
            lines.append(f"\n{padding}")
            lines.append("Or copy this URL into your browser:")
            lines.append(f"\n{padding}")
            lines.append(pairing_url, style="dim")
            lines.append("\n")

        if has_ops:
            if pairing_active:
                lines.append("\n")
            lines.append(self._OPS_SEPARATOR, style="dim")
            for entry in ops_snapshot:
                lines.append("\n")
                lines.append(" \u25cf ", style="dim")
                lines.append(entry.summary, style="dim")
                lines.append(" \u23f3", style="dim")
            lines.append("\n")
            lines.append(self._OPS_SEPARATOR, style="dim")

        return lines

    def _update_live(self) -> None:
        if self._live is not None:
            self._live.refresh()
