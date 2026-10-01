from unittest import mock

import pytest

from opik.configurator.mcp import view as mcp_view


class TestSingleCandidateMenu:
    """One detected client, on a terminal that cannot host the picker.

    It has always been a yes/no, and anything piping input into it sends Y, N or
    a bare Enter — so those keep working. The numbers are what was missing: the
    manual row existed only once two clients were detected, which left the user
    it is for — one client found, and it is not theirs — with no way to reach it.
    """

    @staticmethod
    def _answer(text):
        with mock.patch("builtins.input", lambda prompt: text):
            return mcp_view.numbered_menu(
                "pick", [mcp_view.HostChoice("cursor", "Cursor")]
            )

    @pytest.mark.parametrize("answer", ["y", "Y", "yes", "", "1"])
    def test_yes_in_any_of_its_old_spellings__installs(self, answer):
        assert self._answer(answer) == ["cursor"]

    @pytest.mark.parametrize("answer", ["n", "N", "no", "3"])
    def test_no_in_any_of_its_old_spellings__installs_nothing(self, answer):
        assert self._answer(answer) == []

    def test_not_listed__is_reachable_with_one_client(self):
        assert self._answer("2") == [mcp_view.MANUAL_SETUP]

    def test_the_options_are_named_in_the_prompt(self):
        seen = []
        with mock.patch("builtins.input", lambda prompt: seen.append(prompt) or "y"):
            mcp_view.numbered_menu("pick", [mcp_view.HostChoice("cursor", "Cursor")])

        assert mcp_view.MANUAL_SETUP_LABEL in seen[0]
        assert "Cursor" in seen[0]

    def test_nonsense__asks_again_rather_than_guessing(self):
        answers = iter(["maybe", "y"])
        with mock.patch("builtins.input", lambda prompt: next(answers)):
            chosen = mcp_view.numbered_menu(
                "pick", [mcp_view.HostChoice("cursor", "Cursor")]
            )

        assert chosen == ["cursor"]


class TestNumberedMenuCancel:
    """Ctrl-C at the fallback means what it means at the picker it stands in for.

    The picker answers it with ``None``, which the flow reports as a cancelled
    run. `input()` raised instead, so the same key on a terminal without the
    picker aborted the command before it could report anything.
    """

    @staticmethod
    def _interrupt(prompt):
        raise KeyboardInterrupt

    def test_one_client__ctrl_c__is_a_cancel(self):
        with mock.patch("builtins.input", self._interrupt):
            chosen = mcp_view.numbered_menu(
                "pick", [mcp_view.HostChoice("cursor", "Cursor")]
            )

        assert chosen is None

    def test_several_clients__ctrl_c__is_a_cancel(self):
        with mock.patch("builtins.input", self._interrupt):
            chosen = mcp_view.numbered_menu(
                "pick",
                [
                    mcp_view.HostChoice("cursor", "Cursor"),
                    mcp_view.HostChoice("codex", "Codex"),
                ],
            )

        assert chosen is None


class TestTargetResult:
    def test_short__prefers_the_summary(self):
        result = mcp_view.TargetResult(
            "Cursor", "Added 'opik-mcp' in /long/path", True, "Added"
        )
        assert result.short == "Added"

    def test_short__falls_back_to_detail(self):
        result = mcp_view.TargetResult("Codex", "no codex CLI on PATH", False)
        assert result.short == "no codex CLI on PATH"
