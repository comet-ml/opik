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
import functools
import os
import select
import sys
from typing import Callable, Iterable, List, Optional, Sequence, Set, Tuple

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

#: Byte strings that are the beginning of a key rather than a key. A read can
#: stop after either of them, and neither half means anything alone: `\x1b` is
#: indistinguishable from a bare Escape until the window above expires, and
#: `\x1b[` used to be discarded along with the arrow it was the start of.
_PARTIAL_ESCAPES = (b"\x1b", b"\x1b[")

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
    #: A row that stands for something other than itself — "All", "none of these
    #: is my client". Select-all skips them: ticking a row labelled "my client is
    #: not listed" is not a meaningful part of "all of them", and doing it made
    #: the `a` key resolve to that row and install nothing.
    synthetic: bool = False


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
    real_keys = _real_keys(choices)
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
                # Which rows are ticked, not how many. Counting made a mixed
                # selection — the `All` row plus two of three clients — look
                # identical to a full one, so `a` emptied the list instead of
                # filling it and Enter then fell back to whatever the cursor
                # happened to sit on. Assigning rather than adding keeps the
                # answer to `a` exactly the real rows: a synthetic row that
                # stands for the whole list has no business also being in it.
                if real_keys.issubset(selected):
                    selected.clear()
                else:
                    selected = set(real_keys)

            live.update(_render(title, choices, selected, cursor), refresh=True)

    if len(selected) == 0:
        return [choices[cursor].key]
    return [choice.key for choice in choices if choice.key in selected]


def choose_one(
    title: str,
    choices: Sequence[Choice],
    read_key: Optional[Callable[[], str]] = None,
) -> Optional[str]:
    """Pick exactly one, with the arrow keys *or* by typing its number.

    Both, deliberately. A number was the only way to answer this question before
    there was a picker, so scripts driving a pty and people with the muscle
    memory both still send ``1`` and Enter — a digit moves the cursor to that
    row and Enter takes the row, which makes that sequence mean what it always
    meant. Callers that cannot host a picker at all keep the plain ``input()``
    prompt; see :func:`is_supported`.

    Returns the chosen key, or ``None`` if the user cancelled.
    """
    if len(choices) == 0:
        return None

    reader = read_key or _key_reader()
    if reader is None:
        return None

    cursor = 0
    with rich.live.Live(
        _render_one(title, choices, cursor),
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
            elif key.isdigit() and 1 <= int(key) <= len(choices):
                cursor = int(key) - 1

            live.update(_render_one(title, choices, cursor), refresh=True)

    return choices[cursor].key


def _render_one(
    title: str, choices: Sequence[Choice], cursor: int
) -> console_module.Group:
    """The single-select list: a number per row, so both ways in are visible."""
    grid = table.Table.grid(padding=(0, 2))
    grid.add_column(no_wrap=True)  # cursor
    grid.add_column(no_wrap=True)  # number
    grid.add_column(no_wrap=True)  # label
    grid.add_column(overflow="fold")  # hint

    for index, choice in enumerate(choices):
        is_current = index == cursor
        grid.add_row(
            text.Text(CURSOR if is_current else " ", style="cyan"),
            text.Text(str(index + 1), style="cyan" if is_current else "dim"),
            text.Text(choice.label, style="bold" if is_current else ""),
            text.Text(choice.hint, style="dim"),
        )
    return console_module.Group(
        text.Text(title, style="bold"),
        grid,
        text.Text(
            f"  ↑↓ move · 1-{len(choices)} pick · enter confirm "
            f"({choices[cursor].label})",
            style="dim",
        ),
    )


def _real_keys(choices: Sequence[Choice]) -> Set[str]:
    """The rows that stand for themselves — what "all" and `a` are about."""
    return {choice.key for choice in choices if not choice.synthetic}


def _footer(choices: Sequence[Choice], selected: Set[str], cursor: int) -> str:
    """Spell out what Enter will take, so it is never guessed at."""
    real_keys = _real_keys(choices)
    if len(selected) == 0:
        target = choices[cursor].label
    elif real_keys.issubset(selected):
        # The test `a` uses. Counting against every row instead meant a list
        # carrying an `All` row could never reach this branch, so selecting
        # everything still read as "3 selected".
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


@functools.lru_cache(maxsize=1)
def _key_reader() -> Optional[Callable[[], str]]:
    """The platform key reader, or ``None`` where neither is available.

    Cached, so every prompt in a run shares one reader and with it one buffer.
    A terminal hands over bursts rather than keystrokes, so a pty harness that
    answers several questions at once — ``1\n`` for the deployment, ``y\n`` for
    what follows — can leave the later answers sitting in the read the first
    picker made. Built per prompt, that buffer died with the picker while its
    bytes were already off the tty queue, and the next question waited for input
    nothing would send again. One reader hands them to whoever asks next.
    """
    try:
        import termios  # noqa: F401
        import tty  # noqa: F401
    except ImportError:
        pass
    else:
        return _read_key_posix()

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


def _read_key_posix() -> Callable[[], str]:
    """A reader that returns one key token per call, buffering the rest.

    A terminal hands over a burst, not a keystroke. An arrow key arrives as three
    bytes, and a pty driver writing "1\n" — `pexpect.sendline`, `printf '1\n'` —
    delivers ``b"1\r\n"`` in a single ``os.read``. Interpreting only the first
    byte and dropping the remainder meant the Enter that follows a typed digit
    was consumed from the tty queue and thrown away, so the picker then waited
    for a keypress that had already arrived. Space-then-Enter lost the same way.

    Hence the buffer: read once, hand back one token, keep what is left for the
    next call. Reading the descriptor directly rather than through ``sys.stdin``
    stays as it was — the buffered text stream pulls an arrow key's whole burst
    into userspace, after which ``select()`` on the descriptor sees nothing
    pending and every arrow reads as a cancellation.
    """
    import termios
    import tty

    pending = bytearray()

    def read_key() -> str:
        if not pending:
            descriptor = sys.stdin.fileno()
            saved = termios.tcgetattr(descriptor)
            try:
                # cbreak, not raw: it leaves signal generation alone so Ctrl-C
                # still raises KeyboardInterrupt rather than arriving as a byte
                # we must handle.
                tty.setcbreak(descriptor)
                pending.extend(os.read(descriptor, _READ_CHUNK))
                # An escape sequence the read stopped in the middle of: the
                # continuation may still be in flight. A loop rather than a
                # single question, because the split can land after either
                # byte — stopping after `\x1b[` used to drop the arrow it began,
                # since neither that prefix nor the `A`/`B` arriving alone
                # afterwards resolves to a key.
                while bytes(pending) in _PARTIAL_ESCAPES and _has_pending_input(
                    descriptor
                ):
                    more = os.read(descriptor, _READ_CHUNK)
                    if not more:
                        # End of input. `select` calls a spent descriptor
                        # readable forever, so without this the loop is the
                        # one that never ends.
                        break
                    pending.extend(more)
            except KeyboardInterrupt:
                return CANCEL
            finally:
                termios.tcsetattr(descriptor, termios.TCSADRAIN, saved)

        token, consumed = _take_token(bytes(pending))
        del pending[:consumed]
        return token

    return read_key


def _take_token(data: bytes) -> Tuple[str, int]:
    """The first key token in a buffer, and how many bytes it used."""
    if not data:
        return CANCEL, 0
    if data.startswith(b"\x1b["):
        # A cursor key. Anything we do not map is ignored rather than treated as
        # a cancellation — an unknown sequence must not close the picker.
        return _ARROWS.get(data[2:3].decode("latin1"), ""), min(3, len(data))
    if data == b"\x1b":
        return CANCEL, 1
    return _normalise(data[:1].decode("latin1")), 1


def _interpret(data: bytes) -> str:
    """The token a buffer starts with, ignoring whatever follows it."""
    return _take_token(data)[0]


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
    # Digits pass through as themselves, for :func:`choose_one`'s number
    # shortcuts. `multiselect` ignores them, as it ignores any other key it has
    # no meaning for.
    if char.isdigit():
        return char
    return ""
