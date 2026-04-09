"""TUI — inline display with Rich Live pending panel for bridge ops."""

import threading
from dataclasses import dataclass
from typing import Optional

from rich.console import Console
from rich.live import Live
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


class RunnerTUI:
    def __init__(self, console: Optional[Console] = None) -> None:
        self._console = console or Console()
        self._is_tty = self._console.is_terminal
        self._pending_ops: dict[str, _OpEntry] = {}
        self._line_count = 0
        self._live: Optional[Live] = None
        self._lock = threading.Lock()

    def start(self) -> None:
        if self._is_tty:
            self._live = Live(
                self._build_panel(),
                console=self._console,
                refresh_per_second=8,
                transient=False,
            )
            self._live.start()

    def stop(self) -> None:
        if self._live is not None:
            self._live.stop()
            self._live = None

    def print_banner(
        self,
        runner_id: str,
        project_name: str = "",
        url: str = "",
    ) -> None:
        # Align url/project lines under "runner:" visually
        # "   ⠀⃝ opik  " is ~13 chars wide in a terminal
        padding = " " * 11

        info = Text()
        info.append("   ")
        info.append("\u2800\u20dd", style="rgb(224,62,45)")
        info.append(" opik  ", style="bold")
        info.append(f"runner: {runner_id}", style="dim")
        if url:
            info.append(f"\n{padding}")
            info.append(f"url: {url}", style="dim")
        if project_name:
            info.append(f"\n{padding}")
            info.append(f"project: {project_name}", style="dim")

        self._console.print(info)
        self._console.print()

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
        text.append(" \u2503  Restarting...", style="rgb(80,85,245)")
        self._print(text)

        self._update_live()

    def _print(self, renderable: Text) -> None:
        if self._live is not None:
            self._live.console.print(renderable)
        else:
            self._console.print(renderable)

    def _build_panel(self) -> Text:
        with self._lock:
            if not self._pending_ops:
                return Text("")

        separator = "\u2576" + "\u2500" * 46 + "\u2574"
        lines = Text()
        lines.append(separator, style="dim")
        with self._lock:
            for entry in self._pending_ops.values():
                lines.append("\n")
                lines.append(" \u25cf ", style="dim")
                lines.append(entry.summary, style="dim")
                lines.append(" \u23f3", style="dim")
        lines.append("\n")
        lines.append(separator, style="dim")
        return lines

    def _update_live(self) -> None:
        if self._live is not None:
            self._live.update(self._build_panel())
