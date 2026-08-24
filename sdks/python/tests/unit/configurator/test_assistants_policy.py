"""Tests for the assistant consent policy.

The policy used to live inside ``OpikConfigurator``, so exercising it meant
constructing a configurator and patching four module-level functions. It is a
pure function of its arguments now, so the table below is the whole contract.
"""

import pytest

from opik.configurator import assistants


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
        assert assistants.readable_list(names) == expected

    def test_empty__does_not_raise(self):
        """The CLI's own copy of this indexed [-1] and crashed on an empty list."""
        assert assistants.readable_list([]) == ""


DETECTED = ["Claude Code"]
SKIP, PROCEED, ASK = (
    assistants.Decision.SKIP,
    assistants.Decision.PROCEED,
    assistants.Decision.ASK,
)


class TestMcpDecision:
    @pytest.mark.parametrize(
        "install_mcp, auto, interactive, detected, expected",
        [
            (False, False, True, DETECTED, SKIP),  # explicit no
            (True, False, False, DETECTED, PROCEED),  # flag wins over no-tty
            (True, True, False, DETECTED, PROCEED),  # flag wins over -y
            (None, False, False, DETECTED, SKIP),  # nobody to ask
            (None, True, True, DETECTED, SKIP),  # -y must not touch other tools
            (None, False, True, [], SKIP),  # nothing worth asking about
            (None, False, True, DETECTED, ASK),
        ],
    )
    def test_decision_table(self, install_mcp, auto, interactive, detected, expected):
        assert (
            assistants.mcp_decision(
                install_mcp=install_mcp,
                automatic_approvals=auto,
                interactive=interactive,
                detected=detected,
            )
            is expected
        )


class TestSkillsDecision:
    @pytest.mark.parametrize(
        "install_skills, auto, interactive, detected, expected",
        [
            (False, False, True, DETECTED, SKIP),
            (True, False, False, DETECTED, PROCEED),
            (True, False, False, [], SKIP),  # flag, but nowhere to put it
            (None, False, False, DETECTED, SKIP),
            (None, True, True, DETECTED, SKIP),
            (None, False, True, [], SKIP),
            (None, False, True, DETECTED, ASK),
        ],
    )
    def test_decision_table(
        self, install_skills, auto, interactive, detected, expected
    ):
        assert (
            assistants.skills_decision(
                install_skills=install_skills,
                automatic_approvals=auto,
                interactive=interactive,
                detected=detected,
            )
            is expected
        )

    def test_mirrors_mcp_except_where_it_must_not(self):
        """Both steps share a consent shape; only the empty-detected+flag case differs."""
        common = dict(automatic_approvals=False, interactive=True, detected=DETECTED)
        for flag in (True, False, None):
            assert assistants.mcp_decision(
                install_mcp=flag, **common
            ) is assistants.skills_decision(install_skills=flag, **common)


class TestPrompts:
    def test_mcp_prompt__names_what_was_found(self):
        prompt = assistants.mcp_prompt(["Claude Code", "Cursor"])

        assert "Claude Code and Cursor" in prompt
        assert "(y/N)" in prompt, "defaults to no"

    def test_skills_prompt__is_recommended_and_defaults_to_yes(self):
        assert "Recommended" in assistants.SKILLS_PROMPT
        assert "(Y/n)" in assistants.SKILLS_PROMPT

    def test_skills_prompt__does_not_re_list_the_assistants(self):
        """The server step's results table just named them."""
        assert "Claude Code" not in assistants.SKILLS_PROMPT
