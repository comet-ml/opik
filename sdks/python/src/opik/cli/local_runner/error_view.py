"""Representation layer for CLI errors.

Two concerns live here, deliberately split:

1. :class:`RichClickError` — a generic ``click.ClickException`` that renders
   *any* Rich renderable on ``show()``. Reusable for future error types: just
   pass a different renderable.

2. :func:`build_config_error_block` — a factory that builds the specific
   labelled diagnostic block used by ``opik connect`` / ``opik endpoint``
   config failures (Reason / Workspace / URL / Config / Fix / Docs / Run).

``pairing.py`` and ``_run.py`` only import the factory; ``rich`` lives nowhere
else in the CLI tree.
"""

from typing import IO, Any, List, Optional, Tuple

import click
from rich.console import Console, ConsoleRenderable, Group
from rich.text import Text


_DEFAULT_RUN_COMMAND = "opik configure"


class RichClickError(click.ClickException):
    """Generic ClickException that renders a Rich renderable on ``show()``.

    ``message`` carries a plain-text equivalent so Sentry, logs, and any
    non-Rich consumer (tests using CliRunner without ANSI, piped output) keep
    working. ``renderable`` is what reaches a real terminal.
    """

    exit_code = 1

    def __init__(self, message: str, renderable: ConsoleRenderable) -> None:
        super().__init__(message)
        self._renderable = renderable

    def show(self, file: Optional[IO[str]] = None) -> None:
        # soft_wrap + a generous width prevent Rich from breaking URLs across
        # lines when stderr is piped (CliRunner, CI logs).
        console = Console(file=file, stderr=file is None, soft_wrap=True, width=200)
        console.print(self._renderable)


# ---------------------------------------------------------------------------
# Config-error shape: header + labelled rows + "→ Run: <cmd>" call-to-action.
# ---------------------------------------------------------------------------


def _build_rows(
    reason: str,
    workspace: Optional[str],
    base_url: Optional[str],
    config_file_exists: bool,
    hint: Optional[Any],
) -> List[Tuple[str, str]]:
    # str() coercion guards against unexpected types (Pydantic models, mocks)
    # reaching Rich's Text.append which only accepts str/Text.
    rows: List[Tuple[str, str]] = [("Reason", str(reason))]
    if workspace:
        rows.append(("Workspace", str(workspace)))
    if base_url:
        rows.append(("URL", str(base_url)))
    if not config_file_exists:
        rows.append(("Config", "no file at ~/.opik.config (defaults in use)"))
    if hint is not None:
        fix = getattr(hint, "fix", None)
        if fix:
            rows.append(("Fix", str(fix)))
        docs = getattr(hint, "docs", None)
        if docs:
            rows.append(("Docs", str(docs)))
    return rows


def _resolve_command(command: Optional[str], hint: Optional[Any]) -> str:
    if command:
        return command
    hinted = getattr(hint, "command", None) if hint is not None else None
    return hinted or _DEFAULT_RUN_COMMAND


def _format_config_plain(
    header: str,
    rows: List[Tuple[str, str]],
    command: str,
) -> str:
    label_width = max(len(label) for label, _ in rows)
    body = "\n".join(f"  {label.ljust(label_width)}  {value}" for label, value in rows)
    return f"{header}\n{body}\n\n  → Run: {command}"


def _build_config_renderable(
    header: str,
    rows: List[Tuple[str, str]],
    command: str,
) -> Group:
    label_width = max(len(label) for label, _ in rows)
    parts: List[ConsoleRenderable] = []

    header_line = Text()
    header_line.append("Error: ", style="bold red")
    header_line.append(header, style="bold")
    parts.append(header_line)

    for label, value in rows:
        line = Text()
        line.append("  ")
        line.append(label.ljust(label_width), style="dim")
        line.append("  ")
        line.append(value)
        parts.append(line)

    cta = Text()
    cta.append("\n  → ", style="bold cyan")
    cta.append("Run: ", style="dim")
    cta.append(command, style="bold cyan")
    parts.append(cta)

    return Group(*parts)


def build_config_error_block(
    header: str,
    *,
    reason: str,
    workspace: Optional[str],
    base_url: Optional[str],
    config_file_exists: bool,
    hint: Optional[Any] = None,
    command: Optional[str] = None,
) -> RichClickError:
    """Build the standard config-error block as a :class:`RichClickError`."""
    rows = _build_rows(reason, workspace, base_url, config_file_exists, hint)
    resolved_command = _resolve_command(command, hint)
    plain = _format_config_plain(header, rows, resolved_command)
    renderable = _build_config_renderable(header, rows, resolved_command)
    return RichClickError(plain, renderable)
