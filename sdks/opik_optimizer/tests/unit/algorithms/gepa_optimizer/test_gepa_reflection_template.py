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
        pyproject floor is gepa>=0.1.0 precisely because our template speaks
        the <curr_param>/<side_info> dialect. If this fails, the resolver
        installed a gepa older (or stranger) than the floor permits.
        """
        default = InstructionProposalSignature.default_prompt_template
        assert "<curr_param>" in default
        assert "<side_info>" in default

    @pytest.mark.parametrize("marker", ["<curr_param>", "<side_info>"])
    def test_required_markers_present(self, marker: str) -> None:
        assert marker in TEMPLATE

    def test_output_format_instruction_stays_last(self) -> None:
        """gepa extracts the candidate from ``` blocks, so this must survive."""
        assert TEMPLATE.rstrip().endswith(
            "Provide the new instructions within ``` blocks."
        )


class TestAdditiveOverUpstreamDefault:
    """Our template must be a strict superset of GEPA's default guidance.

    If one of these fails after a gepa upgrade, upstream reworded its default:
    re-read gepa/strategies/instruction_proposal.py and re-sync
    REFLECTION_PROMPT_TEMPLATE deliberately rather than relaxing the assertion.
    """

    def test_every_default_paragraph_is_preserved_verbatim(self) -> None:
        default = InstructionProposalSignature.default_prompt_template
        paragraphs = [p.strip() for p in default.split("\n\n") if p.strip()]
        missing = [p for p in paragraphs if p not in TEMPLATE]
        assert missing == [], (
            "GEPA's default reflection guidance is no longer contained verbatim in "
            f"our template; missing paragraph(s): {missing}"
        )

    def test_template_only_adds_to_the_default(self) -> None:
        default = InstructionProposalSignature.default_prompt_template
        assert len(TEMPLATE) > len(default)

    def test_adds_the_verbatim_preservation_constraint(self) -> None:
        assert "template variable" in TEMPLATE
        assert "verbatim" in TEMPLATE
        # The specific failure mode: swapping a variable for a row's real value.
        assert "never replace one with a concrete value" in TEMPLATE


class TestRendersThroughGepa:
    def test_markers_are_substituted_and_braces_survive(self) -> None:
        """Rendered via gepa's real renderer, not a reimplementation of it."""
        rendered = InstructionProposalSignature.prompt_renderer(
            {
                "current_instruction_doc": "Answer {question} using {context}.",
                "dataset_with_feedback": [
                    {"Inputs": {"question": "2+2?"}, "Feedback": "too verbose"}
                ],
                "prompt_template": TEMPLATE,
            }
        )
        assert isinstance(rendered, str)
        assert "<curr_param>" not in rendered
        assert "<side_info>" not in rendered
        # The seed's variables reach the reflection LM intact...
        assert "Answer {question} using {context}." in rendered
        # ...as do the template's own brace examples (str.replace, not .format).
        assert "{user_input}" in rendered


class TestOptimizerWiring:
    def test_default_template_is_resolved_and_validated(self) -> None:
        optimizer = GepaOptimizer(model="openai/gpt-4o-mini")
        assert optimizer._resolve_reflection_prompt_template() == TEMPLATE

    def test_override_is_honoured(self) -> None:
        override = "Rewrite <curr_param> given <side_info>."
        optimizer = GepaOptimizer(
            model="openai/gpt-4o-mini",
            prompt_overrides={"reflection_prompt_template": override},
        )
        assert optimizer._resolve_reflection_prompt_template() == override

    def test_override_missing_markers_fails_at_construction(self) -> None:
        """Must raise in __init__, not at the gepa hand-off.

        The hand-off happens after the baseline evaluation, so validating there
        would bill the user a full dataset scoring pass before rejecting an
        obviously malformed template.
        """
        with pytest.raises(ValueError, match="<curr_param>"):
            GepaOptimizer(
                model="openai/gpt-4o-mini",
                prompt_overrides={"reflection_prompt_template": "no markers here"},
            )

    def test_non_string_override_fails_with_the_same_context(self) -> None:
        """A non-string override must not escape as a bare TypeError from gepa's
        validator — it fails at construction with the documented message."""
        with pytest.raises(
            ValueError, match="Invalid reflection_prompt_template override"
        ):
            GepaOptimizer(
                model="openai/gpt-4o-mini",
                # The wrong type is the point of this test: PromptOverrides is
                # str-valued, so a caller who ignores that must still get the
                # documented ValueError rather than a bare TypeError.
                prompt_overrides={"reflection_prompt_template": 123},  # type: ignore[dict-item]
            )

    def test_template_swapped_in_after_construction_is_still_caught(self) -> None:
        """__init__ cannot see a later prompts.set(), so resolve re-checks."""
        optimizer = GepaOptimizer(model="openai/gpt-4o-mini")
        optimizer.prompts.set("reflection_prompt_template", "no markers here")
        with pytest.raises(ValueError, match="<side_info>"):
            optimizer._resolve_reflection_prompt_template()


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
        with pytest.raises(RuntimeError, match="gepa>=0.1.0") as exc_info:
            GepaOptimizer(model="openai/gpt-4o-mini")
        # The misdiagnosis is the bug: no override was passed.
        assert "override" not in str(exc_info.value)

    def test_a_valid_new_dialect_override_also_reports_the_version(
        self, old_dialect_gepa
    ) -> None:
        """The dialect is a property of the install, not of this one template.

        A new-dialect override is well-formed for us and unusable on that gepa,
        so the version error is still the honest answer.
        """
        with pytest.raises(RuntimeError, match="gepa>=0.1.0"):
            GepaOptimizer(
                model="openai/gpt-4o-mini",
                prompt_overrides={
                    "reflection_prompt_template": "Rewrite <curr_param> given <side_info>."
                },
            )

    def test_unreadable_default_still_spares_the_builtin_template(
        self, monkeypatch
    ) -> None:
        """Fallback when the dialect can't be read from gepa's default.

        If a future gepa drops or reshapes ``default_prompt_template`` we cannot
        sniff the dialect, so we do not guess a version error from its absence
        (that would break a *compatible* gepa that merely renamed it). Instead,
        a rejection of our own built-in default is attributed to the install,
        since the caller had no hand in it.
        """
        monkeypatch.setattr(
            InstructionProposalSignature, "default_prompt_template", object()
        )
        monkeypatch.setattr(
            InstructionProposalSignature,
            "validate_prompt_template",
            self._old_dialect_validate,
        )
        with pytest.raises(RuntimeError, match="gepa>=0.1.0"):
            GepaOptimizer(model="openai/gpt-4o-mini")

    def test_unreadable_default_still_blames_a_bad_override(self, monkeypatch) -> None:
        """The fallback must not swallow genuine caller errors."""
        monkeypatch.setattr(
            InstructionProposalSignature, "default_prompt_template", object()
        )
        with pytest.raises(
            ValueError, match="Invalid reflection_prompt_template override"
        ):
            GepaOptimizer(
                model="openai/gpt-4o-mini",
                prompt_overrides={"reflection_prompt_template": "no markers here"},
            )


class TestTemplateReachesGepa:
    """Resolving the template is worthless if it never gets handed over.

    Without these, deleting the reflection_prompt_template kwarg from the
    gepa.optimize() call leaves the whole suite green — fix (b) would be
    silently inert.
    """

    def test_default_template_is_passed_to_gepa_optimize(
        self,
        monkeypatch,
        mock_optimization_context,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        captured = run_optimize_capturing_gepa_kwargs(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )

        assert captured["reflection_prompt_template"] == TEMPLATE

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
