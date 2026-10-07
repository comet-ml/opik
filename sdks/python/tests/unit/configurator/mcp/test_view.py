from unittest import mock

import pytest

from opik.configurator.mcp import targets
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


def _registered(*keys, signed_in=(), pending=()):
    """Install results for real clients, as the installer hands them over."""
    return [
        (
            targets.find_target(key),
            targets.InstallResult(
                key,
                True,
                "Added",
                sign_in_attempted=key in signed_in or key in pending,
                sign_in_failed=key in pending,
            ),
        )
        for key in keys
    ]


class TestNextSteps:
    """What an unattended run leaves for each client, read by whoever ran it."""

    def test_hosted__claude_code_not_signed_in__a_step_for_the_user(self):
        """`claude mcp login` refuses to run without a terminal, so an agent
        cannot run it; `/mcp` also covers builds without the command."""
        [step] = mcp_view.next_steps(True, _registered("claude-code"))

        assert "from a terminal with `claude mcp login opik-mcp`" in step
        assert "`/mcp`" in step

    @pytest.mark.parametrize("key", ["claude-code", "codex"])
    def test_hosted__signed_in_by_the_run__only_a_check(self, key):
        [step] = mcp_view.next_steps(True, _registered(key, signed_in=[key]))

        assert "signed in; check with" in step
        assert "login" not in step

    def test_hosted__a_sign_in_that_did_not_finish__comes_first(self):
        [step] = mcp_view.next_steps(True, _registered("codex", pending=["codex"]))

        assert "the sign-in did not finish" in step
        assert step.index("codex mcp login opik-mcp") < step.index("codex mcp list")

    def test_hosted__each_client_its_own_way_in(self):
        opencode, cursor = mcp_view.next_steps(True, _registered("opencode", "cursor"))

        assert "`opencode mcp auth opik-mcp`" in opencode
        assert cursor == "Cursor: sign in from its MCP settings when it asks."

    def test_local__nothing_to_sign_in_to(self):
        steps = mcp_view.next_steps(
            False, _registered("claude-code", "codex", "cursor", "opencode")
        )

        assert steps == [
            "Claude Code: check with `claude mcp list`.",
            "Codex: check with `codex mcp list`.",
            "opencode: check with `opencode mcp list`.",
        ]

    @pytest.mark.parametrize("hosted", [True, False])
    def test_never_claude_mcp_get__it_prints_the_api_key(self, hosted):
        steps = mcp_view.next_steps(hosted, _registered("claude-code"))

        assert not any("claude mcp get" in step for step in steps)

    @pytest.mark.parametrize(
        "client, command",
        [
            ("Claude Code", "`claude mcp login opik-mcp`"),
            ("Codex", "`codex mcp login opik-mcp`"),
        ],
    )
    def test_sign_in_failed__names_that_clients_own_command(self, client, command):
        assert command in mcp_view.sign_in_failed_message(client)
