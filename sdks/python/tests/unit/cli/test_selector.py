"""Tests for the arrow-key multi-select prompt."""

import os
import sys

from unittest import mock

import pytest

from opik.cli import selector


def _choices():
    return [
        selector.Choice("claude-code", "Claude Code"),
        selector.Choice("cursor", "Cursor"),
        selector.Choice("codex", "Codex", hint="heaviest user"),
    ]


def _driver(*keys):
    """A scripted key reader, so the interaction is testable without a tty."""
    sequence = iter(keys)
    return lambda: next(sequence)


class TestMultiselect:
    def test_accept_with_preselection__returns_it_unchanged(self):
        result = selector.multiselect(
            "pick", _choices(), ["cursor"], read_key=_driver(selector.ACCEPT)
        )

        assert result == ["cursor"]

    def test_space_toggles_the_row_under_the_cursor(self):
        result = selector.multiselect(
            "pick", _choices(), [], read_key=_driver(selector.TOGGLE, selector.ACCEPT)
        )

        assert result == ["claude-code"]

    def test_space_twice__deselects_again(self):
        # Toggling back to nothing leaves an empty set, so Enter falls through to
        # the cursor row rather than returning nothing at all.
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(selector.TOGGLE, selector.TOGGLE, selector.ACCEPT),
        )

        assert result == ["claude-code"]

    def test_accept_with_nothing_selected__takes_the_cursor_row(self):
        # The reported bug: with every row pre-ticked, landing on Claude Code and
        # pressing Enter registered all three assistants. A cursor sitting on a
        # checkbox list reads as a radio list, so Enter has to mean "this one".
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(selector.ACCEPT),
        )

        assert result == ["claude-code"]

    def test_accept_with_nothing_selected__takes_the_row_moved_to(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(selector.DOWN, selector.ACCEPT),
        )

        assert result == ["cursor"]

    def test_an_explicit_tick_beats_the_cursor_row(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(
                selector.DOWN, selector.TOGGLE, selector.UP, selector.ACCEPT
            ),
        )

        assert result == ["cursor"]

    def test_down_then_toggle__selects_the_second_row(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(selector.DOWN, selector.TOGGLE, selector.ACCEPT),
        )

        assert result == ["cursor"]

    def test_up_from_the_top__wraps_to_the_bottom(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(selector.UP, selector.TOGGLE, selector.ACCEPT),
        )

        assert result == ["codex"]

    def test_down_past_the_end__wraps_to_the_top(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(
                selector.DOWN,
                selector.DOWN,
                selector.DOWN,
                selector.TOGGLE,
                selector.ACCEPT,
            ),
        )

        assert result == ["claude-code"]

    def test_toggle_all__selects_everything(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(selector.TOGGLE_ALL, selector.ACCEPT),
        )

        assert result == ["claude-code", "cursor", "codex"]

    def test_toggle_all_when_everything_is_selected__clears(self):
        # `a` still clears the set; Enter on an empty set then takes the cursor row.
        result = selector.multiselect(
            "pick",
            _choices(),
            ["claude-code", "cursor", "codex"],
            read_key=_driver(selector.TOGGLE_ALL, selector.ACCEPT),
        )

        assert result == ["claude-code"]

    def test_toggle_all_from_partial__selects_everything(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            ["cursor"],
            read_key=_driver(selector.TOGGLE_ALL, selector.ACCEPT),
        )

        assert result == ["claude-code", "cursor", "codex"]

    def test_cancel__returns_none_not_an_empty_list(self):
        """`None` means "I backed out" — now the only way to pick nothing.

        Enter can no longer return `[]`: on an empty set it takes the cursor row.
        Escape is the deliberate no-op, and callers already report it as one.
        """
        result = selector.multiselect(
            "pick", _choices(), ["cursor"], read_key=_driver(selector.CANCEL)
        )

        assert result is None

    def test_result_order__follows_the_offered_order_not_the_click_order(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver(
                selector.DOWN,
                selector.DOWN,
                selector.TOGGLE,  # codex first
                selector.UP,
                selector.UP,
                selector.TOGGLE,  # then claude-code
                selector.ACCEPT,
            ),
        )

        assert result == ["claude-code", "codex"]

    def test_unrecognised_key__is_ignored(self):
        result = selector.multiselect(
            "pick",
            _choices(),
            [],
            read_key=_driver("", selector.TOGGLE, selector.ACCEPT),
        )

        assert result == ["claude-code"]

    def test_no_choices__is_an_empty_result_without_reading_keys(self):
        reader = mock.Mock(side_effect=AssertionError("must not read keys"))

        assert selector.multiselect("pick", [], [], read_key=reader) == []

    def test_no_reader_and_unsupported_terminal__returns_none(self, monkeypatch):
        """Callers use `None` as the signal to fall back to the numbered menu."""
        monkeypatch.setattr(selector, "_key_reader", lambda: None)

        assert selector.multiselect("pick", _choices(), []) is None


class TestRender:
    def test_render__marks_selection_and_cursor(self):
        rendered = selector._render("pick", _choices(), {"cursor"}, cursor=2)

        with selector.console.capture() as capture:
            selector.console.print(rendered)
        out = capture.get()

        assert selector.CHECKED in out
        assert selector.UNCHECKED in out
        assert selector.CURSOR in out
        assert "pick" in out
        assert "heaviest user" in out
        assert "enter confirm" in out


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
        monkeypatch.setattr(selector, "_key_reader", lambda: (lambda: ""))

        assert selector.is_supported() is True


class TestNormalise:
    @pytest.mark.parametrize(
        ("char", "expected"),
        [
            ("\r", selector.ACCEPT),
            ("\n", selector.ACCEPT),
            (" ", selector.TOGGLE),
            ("a", selector.TOGGLE_ALL),
            ("A", selector.TOGGLE_ALL),
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


class TestFooter:
    """The footer names what Enter will take, so it is never guessed at."""

    def test_nothing_selected__names_the_cursor_row(self):
        assert "(Claude Code)" in selector._footer(_choices(), set(), 0)

    def test_partial_selection__reports_the_count(self):
        assert "(2 selected)" in selector._footer(_choices(), {"cursor", "codex"}, 0)

    def test_everything_selected__says_all(self):
        selected = {"claude-code", "cursor", "codex"}
        assert "(all)" in selector._footer(_choices(), selected, 0)


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="select() takes sockets, not pipes, on Windows — and the msvcrt reader "
    "never calls this: arrows arrive behind \\x00/\\xe0, not behind Escape.",
)
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
            (b" ", selector.TOGGLE),
            (b"a", selector.TOGGLE_ALL),
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
        return selector._read_key_posix()

    def test_arrow_delivered_as_one_burst__is_an_arrow(self, monkeypatch):
        assert self._run(monkeypatch, [b"\x1b[B"]) == selector.DOWN

    def test_bare_escape_with_nothing_pending__cancels(self, monkeypatch):
        assert self._run(monkeypatch, [b"\x1b"], pending=False) == selector.CANCEL

    def test_escape_then_continuation__is_an_arrow_not_a_cancel(self, monkeypatch):
        """A split sequence: the second read completes it."""
        result = self._run(monkeypatch, [b"\x1b", b"[A"], pending=True)

        assert result == selector.UP

    def test_plain_character__needs_only_one_read(self, monkeypatch):
        assert self._run(monkeypatch, [b" "]) == selector.TOGGLE
