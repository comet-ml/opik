"""Tests for the consent policy.

The policy used to be two near-identical decision tables plus an ad-hoc ladder in
the CLI that never called either. It is one pure function now, so the table below
is the whole contract — for both steps and both surfaces.
"""

import pytest

from opik.configurator import consent

SKIP, PROCEED, ASK = (
    consent.Decision.SKIP,
    consent.Decision.PROCEED,
    consent.Decision.ASK,
)
R = consent.Reason


class TestResolve:
    @pytest.mark.parametrize(
        "flag, assume_yes, interactive, detected, expected",
        [
            # An explicit no wins over everything, -y and a terminal included.
            (False, False, True, True, (SKIP, R.DECLINED)),
            (False, True, True, True, (SKIP, R.DECLINED)),
            # An explicit yes proceeds without a terminal: the coding-agent path.
            (True, False, False, True, (PROCEED, R.REQUESTED)),
            (True, True, False, True, (PROCEED, R.REQUESTED)),
            (True, False, True, True, (PROCEED, R.REQUESTED)),
            # Nothing said and nobody to ask.
            (None, False, False, True, (SKIP, R.NO_TERMINAL)),
            # -y is not consent to edit another tool's config.
            (None, True, True, True, (SKIP, R.ASSUME_YES)),
            # Nothing worth asking about.
            (None, False, True, False, (SKIP, R.NOTHING_DETECTED)),
            # The only case that reaches the user.
            (None, False, True, True, (ASK, R.ASKING)),
        ],
    )
    def test_decision_table(self, flag, assume_yes, interactive, detected, expected):
        assert (
            consent.resolve(
                flag,
                assume_yes=assume_yes,
                interactive=interactive,
                anything_detected=detected,
            )
            == expected
        )

    def test_no_terminal_is_reported_before_assume_yes(self):
        """An unattended run must not be told `-y` was the reason.

        The command hands `-y` down whenever there is no tty, so both inputs are
        true at once. Reporting ASSUME_YES here told people who never typed the
        flag that the flag was why their editor was skipped.
        """
        verdict = consent.resolve(
            None, assume_yes=True, interactive=False, anything_detected=True
        )

        assert verdict.reason is R.NO_TERMINAL

    @pytest.mark.parametrize("flag", [False, None])
    def test_unflagged_without_a_terminal_never_proceeds(self, flag):
        """The line between a coding agent that was asked and CI that was not."""
        verdict = consent.resolve(
            flag, assume_yes=False, interactive=False, anything_detected=True
        )

        assert verdict.decision is SKIP


class TestGranted:
    def test_proceed__does_not_ask(self):
        called = []
        verdict = consent.Verdict(PROCEED, R.REQUESTED)

        assert consent.granted(verdict, lambda: called.append(1) or True) is True
        assert called == [], "a decided verdict must not prompt"

    def test_skip__does_not_ask(self):
        called = []
        verdict = consent.Verdict(SKIP, R.DECLINED)

        assert consent.granted(verdict, lambda: called.append(1) or True) is False
        assert called == []

    @pytest.mark.parametrize("answer", [True, False])
    def test_ask__returns_the_answer(self, answer):
        verdict = consent.Verdict(ASK, R.ASKING)

        assert consent.granted(verdict, lambda: answer) is answer


class TestReadableList:
    @pytest.mark.parametrize(
        "names, expected",
        [
            ([], ""),
            (["A"], "A"),
            (["A", "B"], "A and B"),
            (["A", "B", "C"], "A, B and C"),
        ],
    )
    def test_reads_as_a_sentence(self, names, expected):
        assert consent.readable_list(names) == expected


class TestPrompts:
    def test_mcp_prompt__names_what_was_found(self):
        prompt = consent.mcp_prompt(["Claude Code", "Cursor"])

        assert "Claude Code and Cursor" in prompt
        assert "(y/N)" in prompt, "defaults to no"

    def test_skills_prompt__is_recommended_and_defaults_to_yes(self):
        assert "Recommended" in consent.SKILLS_PROMPT
        assert "(Y/n)" in consent.SKILLS_PROMPT

    def test_skills_prompt__does_not_re_list_the_assistants(self):
        """The server step's results table just named them."""
        assert "Claude Code" not in consent.SKILLS_PROMPT
