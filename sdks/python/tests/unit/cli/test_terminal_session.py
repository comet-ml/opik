import os
import subprocess
import sys
import threading
import time

import pytest

from opik.cli import terminal_session


class TestWhereTheCursorEnds:
    """What gets erased after a sign-in that worked: too few rows leave a
    remnant, too many eat the line above."""

    def test_lines__one_row_each(self):
        assert terminal_session.cursor_after("one\ntwo\n", columns=80) == (2, 0)

    def test_a_long_line__wraps_onto_more_rows(self):
        assert terminal_session.cursor_after("x" * 200 + "\n", columns=80) == (3, 0)

    def test_a_full_line__waits_on_its_row_until_more_comes(self):
        assert terminal_session.cursor_after("x" * 80, columns=80) == (0, 80)

    def test_an_unfinished_line__leaves_the_cursor_on_its_row(self):
        assert terminal_session.cursor_after("done\nOr paste here: ", columns=80) == (
            1,
            15,
        )

    def test_an_escape_after_the_last_newline__stays_on_the_new_row(self):
        """Claude Code ends with `\\x1b[?25h` after its last line: judging by the
        last character instead put the cursor a row too high and left the
        first line on screen."""
        text = "If the browser didn't open, visit:\r\n  url\r\nDone.\r\n\x1b[?25h"

        assert terminal_session.cursor_after(text, columns=80) == (3, 0)

    def test_a_redrawn_input_line__goes_back_up_before_rewriting(self):
        """A pasted redirect URL that wraps makes the client's line editor go up
        and redraw it; counting the redraw as new rows erased lines above."""
        line = "Or: " + "a" * 16
        redraw = "\x1b[1A\x1b[1G\x1b[0J" + line[:-1] + "\x1b[10G"

        assert terminal_session.cursor_after(line + redraw + "\r\n", columns=10) == (
            2,
            0,
        )

    def test_escape_sequences__take_no_columns(self):
        styled = "\x1b[32m" + "x" * 80 + "\x1b[0m\n"

        assert terminal_session.cursor_after(styled, columns=80) == (1, 0)

    def test_carriage_returns__redraw_the_same_row(self):
        assert terminal_session.cursor_after("abc\rabcdef\n", columns=80) == (1, 0)

    def test_backspaces__step_back_a_column(self):
        assert terminal_session.cursor_after("abc\b\bX", columns=10) == (0, 2)

    def test_tabs__go_to_the_next_stop_and_can_lead_to_a_wrap(self):
        assert terminal_session.cursor_after("ab\tcdef", columns=10) == (1, 2)

    def test_a_tab_near_the_edge__stops_at_the_last_column(self):
        assert terminal_session.cursor_after("abcdefghi\tX", columns=10) == (0, 10)

    def test_wide_characters__take_two_columns(self):
        assert terminal_session.cursor_after("✓" + "界" * 40 + "\n", columns=80) == (
            2,
            0,
        )


class TestWithoutATerminal:
    """Piped or on Windows there is no terminal to manage: run it as it is."""

    @pytest.fixture(autouse=True)
    def no_terminal(self, monkeypatch):
        monkeypatch.setattr(terminal_session, "_can_proxy", lambda: False)

    def test_shows_the_header_and_returns_the_status(self, monkeypatch, capsys):
        monkeypatch.setattr(
            terminal_session.subprocess,
            "run",
            lambda command: subprocess.CompletedProcess(command, 3),
        )

        returncode = terminal_session.run(
            ["login"], "Signing in…", "Starting", ("Waiting", "hint")
        )

        assert returncode == 3
        assert capsys.readouterr().out == "Signing in…\n"

    def test_ctrl_c__gives_up_on_the_command_not_the_run(self, monkeypatch):
        def interrupted(command):
            raise KeyboardInterrupt

        monkeypatch.setattr(terminal_session.subprocess, "run", interrupted)

        assert (
            terminal_session.run(
                ["login"], "Signing in…", "Starting", ("Waiting", "hint")
            )
            is None
        )


def test_a_character_split_across_reads__is_decoded_whole():
    """`…` is three bytes; a read that ends after the first must not mangle it."""
    import codecs

    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    ellipsis = "…".encode()

    assert decoder.decode(ellipsis[:1]) + decoder.decode(ellipsis[1:]) == "…"


@pytest.mark.skipif(sys.platform == "win32", reason="no pseudo-terminals")
# The fixture's reader thread; the forked child only execs.
@pytest.mark.filterwarnings("ignore:This process .* is multi-threaded")
class TestOnATerminal:
    """The real loop, with a pseudo-terminal standing in for the user's."""

    @pytest.fixture
    def screen(self):
        """The user's terminal: returns what the run wrote to it."""
        import fcntl
        import pty
        import struct
        import termios

        master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
        terminal = open(slave, "r+b", buffering=0)

        # Read as a terminal would: unread output stalls `tty.setraw`.
        output = []

        def drain():
            while True:
                try:
                    data = os.read(master, 4096)
                except OSError:
                    return
                if not data:
                    return
                output.append(data)

        reader = threading.Thread(target=drain, daemon=True)
        reader.start()

        def written():
            terminal.close()
            reader.join(timeout=5)
            return b"".join(output).decode()

        written.terminal = terminal
        yield written
        if not terminal.closed:
            terminal.close()
        reader.join(timeout=5)
        os.close(master)

    @pytest.fixture
    def children(self, monkeypatch):
        import pty

        forked = []
        real_fork = pty.fork

        def fork():
            pid, fd = real_fork()
            if pid:
                forked.append(pid)
            return pid, fd

        monkeypatch.setattr(pty, "fork", fork)
        return forked

    def _run(self, screen, script):
        # Here rather than in the fixture: pytest puts its own stdin and stdout
        # back at the start of each test phase.
        with pytest.MonkeyPatch.context() as patch:
            patch.setattr(sys, "stdin", screen.terminal)
            patch.setattr(sys, "stdout", screen.terminal)
            return terminal_session.run(
                ["sh", "-c", script], "Signing in…", "Starting", ("Waiting", "hint")
            )

    def test_a_sign_in_that_worked__is_erased_up_to_the_header(self, screen):
        """The client ends on `\\x1b[?25h` after its last newline, as Claude Code
        does; the erase must still reach the first line it printed."""
        returncode = self._run(
            screen, r"printf 'Starting auth\nvisit:\n  url\nDone.\n\033[?25h'"
        )

        assert returncode == 0
        assert screen().endswith("Done.\r\n\x1b[?25h\x1b[3F\x1b[J")

    def test_a_failure_inside_the_loop__does_not_leave_the_command_running(
        self, screen, children, monkeypatch
    ):
        def broken(*args):
            raise OSError("terminal gone")

        monkeypatch.setattr(terminal_session.select, "select", broken)

        with pytest.raises(OSError, match="terminal gone"):
            self._run(screen, "sleep 30")

        with pytest.raises(ChildProcessError):
            os.waitpid(children[0], os.WNOHANG)

    def test_input_closing__does_not_spin_while_the_command_finishes(
        self, screen, monkeypatch
    ):
        stdin_fd = screen.terminal.fileno()
        real_read, real_select = os.read, terminal_session.select.select
        watched = []

        def read(fd, size):
            return b"" if fd == stdin_fd else real_read(fd, size)

        def select_at_end_of_input(readers, *rest):
            # A closed input reads as ready, every time it is asked.
            watched.append(list(readers))
            if len(watched) > 1000:
                raise AssertionError("still polling a closed input")
            if stdin_fd in readers:
                return [stdin_fd], [], []
            return real_select(readers, *rest)

        monkeypatch.setattr(terminal_session.os, "read", read)
        monkeypatch.setattr(terminal_session.select, "select", select_at_end_of_input)

        assert self._run(screen, "sleep 0.3; printf 'Starting auth\\nDone.\\n'") == 0
        assert stdin_fd not in watched[-1]


@pytest.mark.skipif(sys.platform == "win32", reason="no pseudo-terminals")
class TestUnattended:
    """`claude mcp login` refuses to start unless stdin is a terminal."""

    def test_the_command_sees_a_terminal(self):
        command = [
            sys.executable,
            "-c",
            "import sys; sys.exit(0 if sys.stdin.isatty() else 3)",
        ]

        assert terminal_session.run_unattended(command, timeout_seconds=30) == 0

    def test_one_that_outlives_the_timeout__is_stopped(self):
        command = [sys.executable, "-c", "import time; time.sleep(30)"]
        started = time.monotonic()

        assert terminal_session.run_unattended(command, timeout_seconds=0.5) is None
        assert time.monotonic() - started < 10

    def test_no_pseudo_terminal_to_be_had__does_not_run(self, monkeypatch):
        """The sign-in is then left for later, not a crash."""
        import pty

        def no_pseudo_terminals_left():
            raise OSError("out of pty devices")

        monkeypatch.setattr(pty, "openpty", no_pseudo_terminals_left)

        assert terminal_session.run_unattended(["claude"], timeout_seconds=1) is None


def test_unattended__on_windows__does_not_run(monkeypatch):
    monkeypatch.setattr(terminal_session.sys, "platform", "win32")

    assert terminal_session.run_unattended(["claude"], timeout_seconds=1) is None
