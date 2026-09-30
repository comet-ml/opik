"""Tests for the arrow-key single-select prompt."""

import os
import sys

from unittest import mock

import pytest

from opik.cli import selector


class TestIsSupported:
    def test_is_supported__not_a_tty__is_false(self, monkeypatch):
        monkeypatch.setattr(selector.sys.stdin, "isatty", lambda: False)

        assert selector.is_supported() is False

    def test_is_supported__tty_but_no_key_reader__is_false(self, monkeypatch):
        monkeypatch.setattr(selector.sys.stdin, "isatty", lambda: True)
        monkeypatch.setattr(selector.sys.stdout, "isatty", lambda: True)
        monkeypatch.setattr(selector, "_key_reader", lambda: None)

        assert selector.is_supported() is False

    def test_is_supported__tty_with_reader__is_true(self, monkeypatch):
        monkeypatch.setattr(selector.sys.stdin, "isatty", lambda: True)
        monkeypatch.setattr(selector.sys.stdout, "isatty", lambda: True)
        monkeypatch.setattr(selector, "_key_reader", lambda: lambda: "")

        assert selector.is_supported() is True


class TestNormalise:
    @pytest.mark.parametrize(
        ("char", "expected"),
        [
            ("\r", selector.ACCEPT),
            ("\n", selector.ACCEPT),
            (" ", ""),
            ("a", ""),
            ("\x03", selector.CANCEL),  # Ctrl-C
            ("\x1b", selector.CANCEL),  # Escape
            ("q", selector.CANCEL),
            ("k", selector.UP),
            ("j", selector.DOWN),
            ("z", ""),
        ],
    )
    def test_normalise(self, char, expected):
        assert selector._normalise(char) == expected

    def test_arrow_tables_cover_both_platforms(self):
        assert selector._ARROWS == {"A": selector.UP, "B": selector.DOWN}
        assert selector._WINDOWS_ARROWS == {"H": selector.UP, "P": selector.DOWN}


class TestPendingInput:
    """`_has_pending_input` is what lets Escape be told from an arrow key.

    A blind second `read(1)` after `\\x1b` blocked until the next keypress, so
    Escape appeared to do nothing and then swallowed whatever followed it.
    """

    def test_no_bytes_waiting__is_false(self):
        read_fd, write_fd = os.pipe()
        try:
            assert selector._has_pending_input(read_fd, timeout=0.01) is False
        finally:
            os.close(read_fd)
            os.close(write_fd)

    def test_bytes_already_buffered__is_true(self):
        """An arrow key arrives as one burst, so its continuation is waiting."""
        read_fd, write_fd = os.pipe()
        try:
            os.write(write_fd, b"[A")
            assert selector._has_pending_input(read_fd, timeout=0.01) is True
        finally:
            os.close(read_fd)
            os.close(write_fd)


class TestInterpret:
    """Terminal bytes to key token — the whole decision, as a pure function.

    Extracted so the arrow-versus-Escape call is testable without a tty. It was
    only reachable through a pty before, which is why the regression below shipped.
    """

    @pytest.mark.parametrize(
        "data, expected",
        [
            (b"\x1b[A", selector.UP),
            (b"\x1b[B", selector.DOWN),
            (b"\x1b", selector.CANCEL),
            (b"\r", selector.ACCEPT),
            (b"\n", selector.ACCEPT),
            (b" ", ""),
            (b"a", ""),
            (b"q", selector.CANCEL),
            (b"\x03", selector.CANCEL),
            (b"k", selector.UP),
            (b"j", selector.DOWN),
            (b"", selector.CANCEL),
        ],
    )
    def test_decision_table(self, data, expected):
        assert selector._interpret(data) == expected

    @pytest.mark.parametrize("data", [b"\x1b[A", b"\x1b[B"])
    def test_arrow_is_never_read_as_cancel(self, data):
        """The reported regression: arrow keys closed the picker.

        `sys.stdin.read(1)` pulled the whole `\\x1b[B` burst into the buffered
        reader and returned only `\\x1b`; `select()` on the descriptor then saw
        nothing pending, because the rest sat in userspace above the kernel. So
        every arrow key looked like a bare Escape.
        """
        assert selector._interpret(data) != selector.CANCEL

    def test_unknown_escape_sequence__is_ignored_not_cancelled(self):
        """Home/End/F-keys must not close the picker."""
        assert selector._interpret(b"\x1b[H") == ""

    def test_arrow_arriving_split__still_reads_as_an_arrow(self):
        """Two reads concatenated is the same input as one burst."""
        assert selector._interpret(b"\x1b" + b"[B") == selector.DOWN


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="POSIX reader; the msvcrt path has no escape ambiguity to resolve.",
)
class TestReadKeyPosixUsesTheDescriptor:
    """The reader must read the descriptor, not the buffered `sys.stdin`.

    This is the shape of the reported bug rather than a restatement of it: with
    `sys.stdin.read(1)`, an arrow key's whole `\x1b[B` burst landed in the
    buffered reader and only `\x1b` came back, after which `select()` on the
    descriptor saw nothing pending and the arrow became a cancellation. Reading
    the descriptor directly is what fixes it, so that is what is asserted.
    """

    @staticmethod
    def _run(monkeypatch, reads, pending=False):
        """Drive the reader with scripted `os.read` results."""
        monkeypatch.setattr(selector.sys, "stdin", mock.Mock(fileno=lambda: 99))
        monkeypatch.setattr(
            selector, "_has_pending_input", lambda descriptor, **kw: pending
        )
        # termios/tty are imported inside the reader (they do not exist on
        # Windows), so patch the modules themselves rather than an attribute of
        # `selector`.
        monkeypatch.setattr("termios.tcgetattr", lambda fd: [])
        monkeypatch.setattr("termios.tcsetattr", lambda *a, **k: None)
        monkeypatch.setattr("tty.setcbreak", lambda fd, *a: None)
        pulls = iter(reads)
        monkeypatch.setattr(selector.os, "read", lambda fd, n: next(pulls))
        return selector._read_key_posix()()

    def test_arrow_delivered_as_one_burst__is_an_arrow(self, monkeypatch):
        assert self._run(monkeypatch, [b"\x1b[B"]) == selector.DOWN

    def test_bare_escape_with_nothing_pending__cancels(self, monkeypatch):
        assert self._run(monkeypatch, [b"\x1b"], pending=False) == selector.CANCEL

    def test_escape_then_continuation__is_an_arrow_not_a_cancel(self, monkeypatch):
        """A split sequence: the second read completes it."""
        result = self._run(monkeypatch, [b"\x1b", b"[A"], pending=True)

        assert result == selector.UP

    def test_plain_character__needs_only_one_read(self, monkeypatch):
        assert self._run(monkeypatch, [b"j"]) == selector.DOWN

    def test_split_after_the_bracket__still_completes_the_arrow(self, monkeypatch):
        """The other place a burst can be cut in half.

        `\x1b` alone was waited on, but `\x1b[` was tokenised on the spot — to
        nothing — and the `B` that followed arrived alone and meant nothing
        either, so the arrow key did nothing at all.
        """
        result = self._run(monkeypatch, [b"\x1b[", b"B"], pending=True)

        assert result == selector.DOWN

    def test_partial_escape_at_end_of_input__stops_reading(self, monkeypatch):
        """`select` calls a spent descriptor readable, forever.

        So "there is more pending" stays true after the input is gone, and an
        empty read is the only thing that can end the wait for a continuation
        that is never coming.
        """
        assert self._run(monkeypatch, [b"\x1b[", b""], pending=True) == ""


class TestABurstYieldsEveryToken:
    """A terminal hands over a burst, not a keystroke.

    An arrow arrives as three bytes, and a pty driver writing "1\\n" —
    `pexpect.sendline`, `printf '1\\n' > pty` — delivers b"1\\r\\n" in a single
    `os.read`. Interpreting only the first byte consumed the rest from the tty
    queue and threw it away, so the Enter after a typed digit never arrived and
    the picker waited for a keypress it had already been given.
    """

    @staticmethod
    def _reader(monkeypatch, reads):
        monkeypatch.setattr(selector.sys, "stdin", mock.Mock(fileno=lambda: 99))
        monkeypatch.setattr(selector, "_has_pending_input", lambda d, **k: False)
        monkeypatch.setattr("termios.tcgetattr", lambda fd: [])
        monkeypatch.setattr("termios.tcsetattr", lambda *a, **k: None)
        monkeypatch.setattr("tty.setcbreak", lambda fd, *a: None)
        pulls = iter(reads)
        monkeypatch.setattr(selector.os, "read", lambda fd, n: next(pulls))
        return selector._read_key_posix()

    def test_digit_then_enter_in_one_read__both_survive(self, monkeypatch):
        read = self._reader(monkeypatch, [b"3\r\n"])

        assert [read(), read()] == ["3", selector.ACCEPT]

    def test_letter_then_enter_in_one_read__both_survive(self, monkeypatch):
        read = self._reader(monkeypatch, [b"j\r"])

        assert [read(), read()] == [selector.DOWN, selector.ACCEPT]

    def test_arrow_then_enter_in_one_read__both_survive(self, monkeypatch):
        read = self._reader(monkeypatch, [b"\x1b[B\r"])

        assert [read(), read()] == [selector.DOWN, selector.ACCEPT]

    def test_the_buffer_is_drained_before_reading_again(self, monkeypatch):
        """A second os.read would block; everything must come from the buffer."""
        read = self._reader(monkeypatch, [b"12\r"])

        assert [read(), read(), read()] == ["1", "2", selector.ACCEPT]

    def test_choose_one__answers_a_pty_shaped_delivery(self, monkeypatch):
        """The whole point: the deployment question stays scriptable."""
        read = self._reader(monkeypatch, [b"2\r\n"])
        choices = [selector.Choice(key=str(i), label=f"opt{i}") for i in (1, 2, 3)]

        assert selector.choose_one("Pick", choices, read_key=read) == "2"


class TestOneReaderPerRun:
    """Every prompt shares a reader, so a burst outlives the prompt it landed in.

    A terminal hands over whatever has been typed, not one keystroke: a pty
    harness answering the flow in one go — `1\n` for the deployment, then the
    next answer — puts the later bytes in the read the first picker makes. Built
    per prompt, that buffer died with the picker while its bytes were already
    off the tty queue, and the next question waited for input nothing would send
    again.
    """

    def test_the_reader_is_built_once(self):
        selector._key_reader.cache_clear()
        try:
            assert selector._key_reader() is selector._key_reader()
        finally:
            selector._key_reader.cache_clear()

    @pytest.mark.skipif(
        sys.platform == "win32",
        reason="POSIX reader; the msvcrt path holds no buffer to carry over.",
    )
    def test_what_one_prompt_did_not_use__answers_the_next(self, monkeypatch):
        monkeypatch.setattr(selector.sys, "stdin", mock.Mock(fileno=lambda: 99))
        monkeypatch.setattr(selector, "_has_pending_input", lambda d, **k: False)
        monkeypatch.setattr("termios.tcgetattr", lambda fd: [])
        monkeypatch.setattr("termios.tcsetattr", lambda *a, **k: None)
        monkeypatch.setattr("tty.setcbreak", lambda fd, *a: None)
        # Both answers arrive in one read, and there is no second one to fall
        # back on: a reader that dropped the leftovers would block here.
        pulls = iter([b"1\r2\r"])
        monkeypatch.setattr(selector.os, "read", lambda fd, n: next(pulls))
        choices = [selector.Choice(key=str(i), label=f"opt{i}") for i in (1, 2, 3)]

        selector._key_reader.cache_clear()
        try:
            first = selector.choose_one("Deployment", choices)
            second = selector.choose_one("Something else", choices)
        finally:
            selector._key_reader.cache_clear()

        assert [first, second] == ["1", "2"]


class TestTakeToken:
    @pytest.mark.parametrize(
        ("data", "token", "consumed"),
        [
            (b"", selector.CANCEL, 0),
            (b"\r", selector.ACCEPT, 1),
            (b"\x1b", selector.CANCEL, 1),
            (b"\x1b[A", selector.UP, 3),
            (b"\x1b[", "", 2),
            (b"q", selector.CANCEL, 1),
            (b"7", "7", 1),
        ],
    )
    def test_reports_what_it_used(self, data, token, consumed):
        assert selector._take_token(data) == (token, consumed)
