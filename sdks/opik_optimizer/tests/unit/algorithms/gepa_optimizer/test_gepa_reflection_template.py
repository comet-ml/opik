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

TEMPLATE = gepa_prompts.REFLECTION_PROMPT_TEMPLATE


class TestTemplateContract:
    def test_gepa_accepts_the_template(self) -> None:
        """gepa validates the markers itself and raises without them."""
        InstructionProposalSignature.validate_prompt_template(TEMPLATE)

    @pytest.mark.parametrize("marker", ["<curr_param>", "<side_info>"])
    def test_required_markers_present(self, marker: str) -> None:
        assert marker in TEMPLATE

    def test_output_format_instruction_stays_last(self) -> None:
        """gepa extracts the candidate from ``` blocks, so this must survive."""
        assert TEMPLATE.rstrip().endswith("Provide the new instructions within ``` blocks.")


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

    def test_override_missing_markers_fails_fast(self) -> None:
        """Better a clear error at setup than a crash deep inside the search."""
        optimizer = GepaOptimizer(
            model="openai/gpt-4o-mini",
            prompt_overrides={"reflection_prompt_template": "no markers here"},
        )
        with pytest.raises(ValueError, match="<curr_param>"):
            optimizer._resolve_reflection_prompt_template()
