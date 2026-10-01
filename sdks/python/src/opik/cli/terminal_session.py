"""Run an interactive client command behind a header, and tidy up after it.

A client's own sign-in (`claude mcp login`) prints several lines while it waits on
the browser: where to go, what it is waiting for, a prompt to paste a redirect
URL. Those matter while the user is acting on them and are noise once the sign-in
has worked. Running the command on a pseudo-terminal this process controls keeps
it fully interactive — it still sees a terminal, so its paste prompt works — while
letting the output be replaced and, on success, erased.
"""

import codecs
import os
import re
import select
import signal
import subprocess
import sys
import unicodedata
from typing import List, Optional, Tuple

#: A CSI escape (`ESC [ parameters final`), an OSC one (`ESC ] ... BEL/ST`),
#: any other two-character escape, or one printed character.
_TOKEN = re.compile(
    r"\x1b\[([0-?]*)[ -/]*([@-~])|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b.|(.)",
    re.DOTALL,
)

_CHUNK = 4096


def cursor_after(text: str, columns: int) -> Tuple[int, int]:
    """Where the cursor ends, as ``(row, column)`` from where it started, once
    ``text`` is printed at ``columns`` wide."""
    row, column = 0, 0
    for match in _TOKEN.finditer(text):
        parameters, command, char = match.groups()
        if command:
            # The moves the client's line editor makes to redraw a pasted
            # redirect URL that wraps.
            count = int(parameters) if parameters.isdigit() else 1
            if command == "A":
                row -= count
            elif command == "B":
                row += count
            elif command == "C":
                column += count
            elif command == "D":
                column = max(column - count, 0)
            elif command == "G":
                column = count - 1
        elif char is None:
            continue
        elif char == "\n":
            row, column = row + 1, 0
        elif char == "\r":
            column = 0
        elif char == "\b":
            column = max(column - 1, 0)
        elif char == "\t":
            column = min(column + 8 - column % 8, columns - 1)
        elif unicodedata.category(char)[0] != "C":
            width = 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
            if column + width > columns:
                row, column = row + 1, 0
            column += width
    return row, column


def run(
    command: List[str],
    header: str,
    hide_first_line: str,
    hint_after: Tuple[str, str],
) -> Optional[int]:
    """Run ``command`` interactively under ``header``; erase it all if it succeeds.

    ``header`` is printed at once, so the user sees something the moment the
    command starts. The command's first line is dropped if it starts with
    ``hide_first_line`` (its own version of the header), and its next output
    replaces the header. ``hint_after`` is ``(marker, hint)``: the hint is shown
    once, after the first line containing the marker. On a zero exit everything
    shown is erased; otherwise it stays, so a failure can be read.

    Returns the exit status, or ``None`` if the command could not be run.
    """
    if not _can_proxy():
        sys.stdout.write(f"{header}\n")
        sys.stdout.flush()
        try:
            return subprocess.run(command).returncode
        except OSError:
            return None
        except KeyboardInterrupt:
            # Ctrl-C reaches this process too: give up on the command, not the run.
            print()
            return None
    return _run_on_pty(command, header, hide_first_line, hint_after)


def _can_proxy() -> bool:
    if sys.platform == "win32":
        return False
    try:
        return os.isatty(sys.stdin.fileno()) and os.isatty(sys.stdout.fileno())
    except (AttributeError, OSError, ValueError):
        return False


def _run_on_pty(
    command: List[str],
    header: str,
    hide_first_line: str,
    hint_after: Tuple[str, str],
) -> Optional[int]:
    import fcntl
    import pty
    import struct
    import termios
    import tty

    stdin_fd, stdout_fd = sys.stdin.fileno(), sys.stdout.fileno()
    columns, lines = os.get_terminal_size(stdout_fd)

    os.write(stdout_fd, f"{header}\n".encode())
    header_shown = True
    shown = ""  # What the command has put on screen, for erasing it later.
    pending = ""  # Output held back until the first line is known.
    first_line_seen = False
    # Incremental, because a read can end halfway through a multi-byte
    # character such as the "…" the client prints.
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    marker, hint = hint_after
    hint_shown = False

    saved = termios.tcgetattr(stdin_fd)
    pid, master_fd = pty.fork()
    if pid == 0:
        try:
            os.execvp(command[0], command)
        finally:
            os._exit(127)

    watched = [stdin_fd, master_fd]
    try:
        fcntl.ioctl(
            master_fd, termios.TIOCSWINSZ, struct.pack("HHHH", lines, columns, 0, 0)
        )
        # Raw, so every key — Ctrl-C included — goes to the command, whose own
        # terminal then echoes it and turns ^C into its signal.
        tty.setraw(stdin_fd)
        while True:
            readable, _, _ = select.select(watched, [], [])
            if stdin_fd in readable:
                data = os.read(stdin_fd, _CHUNK)
                if data:
                    os.write(master_fd, data)
                else:
                    # Input has closed; the command can still finish without it.
                    watched.remove(stdin_fd)
            if master_fd in readable:
                try:
                    data = os.read(master_fd, _CHUNK)
                except OSError:
                    break  # The command has exited and closed its terminal.
                if not data:
                    break
                text = decoder.decode(data)
                if not first_line_seen:
                    pending += text
                    if "\n" not in pending:
                        continue
                    first, _, text = pending.partition("\n")
                    first_line_seen, pending = True, ""
                    if not first.strip().startswith(hide_first_line):
                        text = f"{first}\n{text}"
                if not text:
                    continue
                if header_shown:
                    # The command's own lines take the header's place.
                    os.write(stdout_fd, b"\x1b[1F\x1b[2K")
                    header_shown = False
                if not hint_shown and marker in (shown + text):
                    # After the marker's line, before whatever comes next.
                    head, newline, tail = (
                        (shown + text).partition(marker)[2].partition("\n")
                    )
                    if newline:
                        cut = len(text) - len(tail)
                        text = f"{text[:cut]}\x1b[2m{hint}\x1b[0m\r\n{text[cut:]}"
                        hint_shown = True
                os.write(stdout_fd, text.encode())
                shown += text
    except BaseException:
        # Whatever failed here, the command must not outlive it: closing our end
        # alone does not reach a command that has not taken the terminal yet.
        os.kill(pid, signal.SIGTERM)
        raise
    finally:
        os.close(master_fd)
        _, status = os.waitpid(pid, 0)
        termios.tcsetattr(stdin_fd, termios.TCSADRAIN, saved)

    returncode = os.waitstatus_to_exitcode(status)
    # Tracked by where the cursor is, not by the last character: the client
    # ends on an escape code after its last newline.
    row, column = cursor_after(shown, columns)
    if returncode == 0:
        # Back to the first row of what was shown, then clear to the end.
        up = row + (1 if header_shown else 0)
        os.write(stdout_fd, (f"\x1b[{up}F" if up else "\r").encode() + b"\x1b[J")
    elif column:
        os.write(stdout_fd, b"\r\n")
    return returncode
