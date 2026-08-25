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
import os
import select
import sys
from typing import Callable, Iterable, List, Optional, Sequence, Set

import rich.console
import rich.live
from rich import console as console_module
from rich import table, text

console = rich.console.Console()

#: How long to wait for an escape sequence's continuation before concluding the
#: user pressed a bare Escape. What matters is the gap *within* one burst, not
#: network latency: a terminal writes "\x1b[A" in a single write, and SSH delays
#: the whole burst rather than spacing its bytes out, so the real gap is ~0. The
#: window is generous because the only cost of a large one is that a bare Escape
#: takes this long to register, while the cost of too small a one is an arrow key
#: being misread as cancellation.
ESCAPE_WINDOW = 0.12

#: Enough for any cursor-key sequence in one read.
_READ_CHUNK = 8

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

    With nothing ticked, Enter takes the row under the cursor. A checkbox list
    with a cursor on it reads as a radio list to plenty of people, so "move to
    Claude Code, press Enter" has to mean Claude Code — it previously confirmed
    the whole pre-ticked set and wrote into three tools' configs at once. Ticking
    anything switches to the subset the user built, and the footer names whichever
    of the two Enter is about to do.

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

    if len(selected) == 0:
        return [choices[cursor].key]
    return [choice.key for choice in choices if choice.key in selected]


def _footer(choices: Sequence[Choice], selected: Set[str], cursor: int) -> str:
    """Spell out what Enter will take, so it is never guessed at."""
    if len(selected) == 0:
        target = choices[cursor].label
    elif len(selected) == len(choices):
        target = "all"
    else:
        target = f"{len(selected)} selected"
    return f"  ↑↓ move · space select · a all · enter confirm ({target})"


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
        text.Text(_footer(choices, selected, cursor), style="dim"),
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


def _has_pending_input(descriptor: int, timeout: float = ESCAPE_WINDOW) -> bool:
    """Whether more bytes are already waiting, so ESC can be told from ESC-[.

    A terminal emits an arrow key's whole escape sequence in one burst, while a
    bare Escape arrives alone. A short select() tells them apart and keeps Escape
    responsive instead of blocking on the next keypress.

    POSIX only, and only ever reached from :func:`_read_key_posix`: on Windows
    ``select()`` accepts sockets rather than arbitrary descriptors, and the
    ``msvcrt`` reader needs none of this — there, arrows arrive behind a
    ``\x00``/``\xe0`` prefix instead of behind Escape, so nothing is ambiguous.
    """
    ready, _, _ = select.select([descriptor], [], [], timeout)
    return bool(ready)


def _read_key_posix() -> str:
    """Read one keypress, telling a bare Escape from a cursor-key sequence.

    Reads the descriptor directly rather than through ``sys.stdin``. The buffered
    text stream pulls an arrow key's whole ``\x1b[B`` burst into its userspace
    buffer and hands back only the ``\x1b`` — after which ``select()`` on the
    descriptor sees nothing pending, because the rest is already buffered above
    the kernel. That combination read every arrow key as a cancellation. Going
    unbuffered keeps the descriptor the single source of truth.
    """
    import termios
    import tty

    descriptor = sys.stdin.fileno()
    saved = termios.tcgetattr(descriptor)
    try:
        # cbreak, not raw: it leaves signal generation alone so Ctrl-C still
        # raises KeyboardInterrupt rather than arriving as a byte we must handle.
        tty.setcbreak(descriptor)
        data = os.read(descriptor, _READ_CHUNK)
        if data == b"\x1b" and _has_pending_input(descriptor):
            # A bare Escape so far, but the continuation may still be in flight.
            data += os.read(descriptor, _READ_CHUNK)
        return _interpret(data)
    except KeyboardInterrupt:
        return CANCEL
    finally:
        termios.tcsetattr(descriptor, termios.TCSADRAIN, saved)


def _interpret(data: bytes) -> str:
    """Turn one read of terminal bytes into a key token."""
    if not data:
        return CANCEL
    if data.startswith(b"\x1b["):
        # A cursor key. Anything we do not map is ignored rather than treated as
        # a cancellation — an unknown sequence must not close the picker.
        return _ARROWS.get(data[2:3].decode("latin1"), "")
    if data == b"\x1b":
        return CANCEL
    return _normalise(data[:1].decode("latin1"))


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
