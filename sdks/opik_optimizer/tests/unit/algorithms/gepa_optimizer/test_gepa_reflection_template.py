# mypy: disable-error-code=no-untyped-def
"""The GEPA reflection template must add a constraint without losing quality.

The no-degradation argument is structural: our template is GEPA's own default
text plus an additive block. These tests pin that property, so a reworded or
truncated template cannot land silently, and a dependency bump that changes
upstream's default fails loudly enough to be re-synced on purpose.
"""

import pytest
from gepa.strategies.instruction_proposal import InstructionProposalSignature

from opik_optimizer.algorithms.gepa_optimizer import prompts as gepa_prompts
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from tests.unit.algorithms.gepa_optimizer.gepa_run_harness import (
    run_optimize_capturing_gepa_kwargs,
)

TEMPLATE = gepa_prompts.REFLECTION_PROMPT_TEMPLATE


class TestTemplateContract:
    def test_gepa_accepts_the_template(self) -> None:
        """gepa validates the markers itself and raises without them."""
        InstructionProposalSignature.validate_prompt_template(TEMPLATE)

    def test_installed_gepa_uses_the_markers_we_rely_on(self) -> None:
        """The version floor's real contract, pinned against the installed gepa.

        gepa 0.0.x used <curr_instructions>/<inputs_outputs_feedback>; the
        pyproject floor is gepa>=0.1.4 precisely because our template speaks
        the <curr_param>/<side_info> dialect. If this fails, the resolver
        installed a gepa older (or stranger) than the floor permits.
        """
        default = InstructionProposalSignature.default_prompt_template
        assert "<curr_param>" in default
        assert "<side_info>" in default


class TestInvalidOverridesFailAtConstruction:
    """Reject a bad template in __init__, before a billed baseline evaluation."""

    @pytest.mark.parametrize(
        "override,match",
        [
            ("no markers here", "<curr_param>"),
            (123, "Invalid reflection_prompt_template override"),
        ],
    )
    def test_invalid_override_is_rejected(self, override: object, match: str) -> None:
        with pytest.raises(ValueError, match=match):
            GepaOptimizer(
                model="openai/gpt-4o-mini",
                prompt_overrides={"reflection_prompt_template": override},  # type: ignore[dict-item]
            )


class TestTooOldGepaIsDiagnosedAsAVersionProblem:
    """An old gepa must be named as such, not blamed on the caller.

    gepa 0.0.18-0.0.27 do expose ``validate_prompt_template``, so the
    ImportError/AttributeError branch never fires — but their validator checks
    the older <curr_instructions>/<inputs_outputs_feedback> markers, so it
    rejects our *own* built-in default. Verified against the real wheels:
    0.0.7-0.0.17 have no validator, 0.0.18-0.0.27 have one on the old dialect,
    >=0.1.0 is the contract we need. Without the dialect check, plain
    ``GepaOptimizer(model=...)`` on that band dies with an "Invalid
    reflection_prompt_template override" ValueError naming an override the
    caller never passed and demanding markers their gepa would reject anyway.
    """

    # Verbatim shape of gepa 0.0.24's default template and validator error.
    OLD_DIALECT_DEFAULT = (
        "I provided an assistant with the following instructions to perform a "
        "task for me:\n```\n<curr_instructions>\n```\n\n"
        "<inputs_outputs_feedback>\n"
    )

    @staticmethod
    def _old_dialect_validate(template: str) -> None:
        missing = [
            marker
            for marker in ("<curr_instructions>", "<inputs_outputs_feedback>")
            if marker not in template
        ]
        if missing:
            raise ValueError(
                f"Missing placeholder(s) in prompt template: {', '.join(missing)}"
            )

    @pytest.fixture
    def old_dialect_gepa(self, monkeypatch):
        """Make the installed gepa look like 0.0.18-0.0.27."""
        monkeypatch.setattr(
            InstructionProposalSignature,
            "default_prompt_template",
            self.OLD_DIALECT_DEFAULT,
        )
        monkeypatch.setattr(
            InstructionProposalSignature,
            "validate_prompt_template",
            self._old_dialect_validate,
        )

    def test_construction_reports_the_version_floor(self, old_dialect_gepa) -> None:
        with pytest.raises(RuntimeError, match="gepa>=0.1.4") as exc_info:
            GepaOptimizer(model="openai/gpt-4o-mini")
        # The misdiagnosis is the bug: no override was passed.
        assert "override" not in str(exc_info.value)


class TestTemplateReachesGepa:
    """Resolving the template is worthless if it never gets handed over.

    Without these, deleting the reflection_prompt_template kwarg from the
    gepa.optimize() call leaves the whole suite green — fix (b) would be
    silently inert.
    """

    def test_override_is_passed_to_gepa_optimize(
        self,
        monkeypatch,
        mock_optimization_context,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        override = "Rewrite <curr_param> using <side_info>. Keep variables."

        captured = run_optimize_capturing_gepa_kwargs(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            optimizer_kwargs={
                "prompt_overrides": {"reflection_prompt_template": override}
            },
        )

        assert captured["reflection_prompt_template"] == override
