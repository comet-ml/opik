import subprocess

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
