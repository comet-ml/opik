"""An arrow-key multi-select prompt, built on stdlib key reading and ``rich``.

Typing ``1,3`` from a numbered menu works, but it makes the user do the mapping
from label to number and offers no feedback until they hit Enter. A checkbox list
they move through with the arrow keys shows the current state at all times.

Hand-rolled deliberately: the alternative is adding ``prompt_toolkit`` (via
``questionary`` or similar) to the core SDK's dependency list, which is a large
addition to every Opik install for one CLI nicety. Everything here is stdlib
``termios``/``msvcrt`` plus ``rich``, which the CLI already depends on.

Not every terminal can do this — a pipe, a CI log, a dumb terminal, a platform
without either key-reading module. :func:`is_supported` says so, and callers fall
back to the numbered menu rather than failing.
"""

import dataclasses
import sys
from typing import Callable, Iterable, List, Optional, Sequence, Set

import rich.console
import rich.live
from rich import console as console_module
from rich import table, text

console = rich.console.Console()

CURSOR = "❯"
CHECKED = "◉"
UNCHECKED = "◯"

# Normalised key tokens produced by the readers below.
UP = "up"
DOWN = "down"
TOGGLE = "toggle"
ACCEPT = "accept"
TOGGLE_ALL = "toggle-all"
CANCEL = "cancel"


@dataclasses.dataclass
class Choice:
    key: str
    label: str
    hint: str = ""


def is_supported() -> bool:
    """Whether this terminal can host an interactive picker."""
    if not (sys.stdin.isatty() and sys.stdout.isatty()):
        return False
    return _key_reader() is not None


def multiselect(
    title: str,
    choices: Sequence[Choice],
    preselected: Optional[Iterable[str]] = None,
    read_key: Optional[Callable[[], str]] = None,
) -> Optional[List[str]]:
    """Let the user tick a subset of ``choices``.

    Returns the selected keys in the order they were offered, or ``None`` if the
    user cancelled (Escape or Ctrl-C) — which is distinct from an empty list,
    meaning "I deliberately chose nothing".

    ``read_key`` is injectable so the interaction can be tested without a tty.
    """
    if len(choices) == 0:
        return []

    reader = read_key or _key_reader()
    if reader is None:
        return None

    selected: Set[str] = set(preselected or ())
    cursor = 0

    with rich.live.Live(
        _render(title, choices, selected, cursor),
        console=console,
        auto_refresh=False,
        transient=False,
    ) as live:
        while True:
            key = reader()

            if key == CANCEL:
                return None
            if key == ACCEPT:
                break
            if key == UP:
                cursor = (cursor - 1) % len(choices)
            elif key == DOWN:
                cursor = (cursor + 1) % len(choices)
            elif key == TOGGLE:
                choice_key = choices[cursor].key
                selected.symmetric_difference_update({choice_key})
            elif key == TOGGLE_ALL:
                if len(selected) == len(choices):
                    selected.clear()
                else:
                    selected = {choice.key for choice in choices}

            live.update(_render(title, choices, selected, cursor), refresh=True)

    return [choice.key for choice in choices if choice.key in selected]


def _render(
    title: str, choices: Sequence[Choice], selected: Set[str], cursor: int
) -> console_module.Group:
    # Title and footer are rendered outside the grid: as grid rows their text
    # sizes the label column, pushing every hint far to the right.
    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(no_wrap=True)  # cursor
    grid.add_column(no_wrap=True)  # checkbox
    grid.add_column(no_wrap=True)  # label
    grid.add_column(overflow="fold")  # hint

    for index, choice in enumerate(choices):
        is_current = index == cursor
        is_selected = choice.key in selected
        grid.add_row(
            text.Text(CURSOR if is_current else " ", style="cyan"),
            text.Text(
                CHECKED if is_selected else UNCHECKED,
                style="green" if is_selected else "dim",
            ),
            text.Text(choice.label, style="bold" if is_current else ""),
            text.Text(choice.hint, style="dim"),
        )
    return console_module.Group(
        text.Text(title, style="bold"),
        grid,
        text.Text("  ↑↓ move · space select · a all · enter confirm", style="dim"),
    )


def _key_reader() -> Optional[Callable[[], str]]:
    """The platform key reader, or ``None`` where neither is available."""
    try:
        import termios  # noqa: F401
        import tty  # noqa: F401
    except ImportError:
        pass
    else:
        return _read_key_posix

    try:
        import msvcrt  # noqa: F401
    except ImportError:
        return None
    return _read_key_windows


def _read_key_posix() -> str:
    import termios
    import tty

    descriptor = sys.stdin.fileno()
    saved = termios.tcgetattr(descriptor)
    try:
        # cbreak, not raw: it leaves signal generation alone so Ctrl-C still
        # raises KeyboardInterrupt rather than arriving as a byte we must handle.
        tty.setcbreak(descriptor)
        first = sys.stdin.read(1)
        if first == "\x1b":
            # Either a bare Escape or the start of a cursor-key sequence. The
            # bracket-then-letter form is what every terminal we care about sends.
            if sys.stdin.read(1) != "[":
                return CANCEL
            return _ARROWS.get(sys.stdin.read(1), "")
        return _normalise(first)
    except KeyboardInterrupt:
        return CANCEL
    finally:
        termios.tcsetattr(descriptor, termios.TCSADRAIN, saved)


def _read_key_windows() -> str:
    # The `sys.platform` guard is what lets a type checker on macOS or Linux skip
    # this body: `msvcrt` has Windows-only stubs and is otherwise an unresolved
    # attribute error on every other platform.
    if sys.platform != "win32":  # pragma: no cover - platform guard
        return ""

    import msvcrt

    try:
        first = msvcrt.getwch()
    except KeyboardInterrupt:
        return CANCEL
    # Arrows arrive as a two-character sequence behind one of these prefixes.
    if first in ("\x00", "\xe0"):
        return _WINDOWS_ARROWS.get(msvcrt.getwch(), "")
    return _normalise(first)


_ARROWS = {"A": UP, "B": DOWN}
_WINDOWS_ARROWS = {"H": UP, "P": DOWN}


def _normalise(char: str) -> str:
    if char in ("\r", "\n"):
        return ACCEPT
    if char == " ":
        return TOGGLE
    if char in ("a", "A"):
        return TOGGLE_ALL
    if char in ("\x03", "\x1b", "q"):
        return CANCEL
    if char == "k":
        return UP
    if char == "j":
        return DOWN
    return ""
