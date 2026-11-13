"""
Unit tests for opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops module.

Tests cover:
- sanitize_generated_prompts: Data leakage detection and removal
- _format_agent_prompts_for_prompt: Agent prompt formatting
- AgentBundleCandidate: Dataclass behavior
"""

from typing import Any

import pytest

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops import (
    sanitize_generated_prompts,
)
from tests.unit.algorithms.meta_prompt_optimizer._meta_prompt_test_helpers import (
    make_prompt_json,
    make_system_prompt_json,
)
from tests.unit.fixtures import system_message, user_message

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


def _sanitize_system_prompts(
    contents: list[str], *, metric_name: str = "my_metric"
) -> dict[str, Any]:
    """Helper to build system-only prompt candidates and run sanitization."""
    prompt_json = make_system_prompt_json(contents)
    return sanitize_generated_prompts(prompt_json, metric_name)


def _assert_single_prompt(result: dict[str, Any]) -> dict[str, Any]:
    assert len(result["prompts"]) == 1
    return result["prompts"][0]


METRIC_LEAKAGE_PATTERNS = [
    "f1 score",
    "f1-score",
    "token-level",
    "exact match",
    "bleu",
    "meteor",
    "rogue",
]

DATASET_LEAKAGE_PATTERNS = [
    "supporting_facts",
    "supporting facts",
    "answer field",
    "context field",
    "question field",
    "training data",
]

EVALUATION_LEAKAGE_PATTERNS = [
    "Match the ground truth exactly.",
    "Compare against the gold standard.",
    "Optimize for the evaluation metric.",
    "Focus on the scoring function.",
]

BENCHMARK_DATASET_NAMES = [
    "hotpotqa",
]


class TestSanitizeGeneratedPrompts:
    """Tests for sanitize_generated_prompts function."""

    def test_removes_prompts_with_metric_name(self) -> None:
        """Should remove prompts containing the metric name."""
        result = _sanitize_system_prompts(
            ["Optimize for accuracy_score metric.", "Be helpful and concise."],
            metric_name="accuracy_score",
        )

        kept = _assert_single_prompt(result)
        assert "accuracy_score" not in kept["edits"][0]["text"]

    @pytest.mark.parametrize("pattern", METRIC_LEAKAGE_PATTERNS)
    def test_removes_metric_patterns(self, pattern: str) -> None:
        """Should remove prompts containing common metric patterns."""
        result = _sanitize_system_prompts([f"Optimize for {pattern}.", "Clean prompt."])
        kept = _assert_single_prompt(result)
        assert "Clean prompt" in kept["edits"][0]["text"]

    @pytest.mark.parametrize("pattern", DATASET_LEAKAGE_PATTERNS)
    def test_removes_dataset_patterns(self, pattern: str) -> None:
        """Should remove prompts containing dataset-specific patterns."""
        result = _sanitize_system_prompts([f"Use the {pattern}.", "Clean prompt."])
        kept = _assert_single_prompt(result)
        assert "Clean prompt" in kept["edits"][0]["text"]

    @pytest.mark.parametrize("content", EVALUATION_LEAKAGE_PATTERNS)
    def test_removes_evaluation_terms(self, content: str) -> None:
        """Should remove prompts referencing evaluation/scoring terms."""
        result = _sanitize_system_prompts(
            [content, "Safe prompt."], metric_name="test_metric"
        )
        kept = _assert_single_prompt(result)
        assert "Safe prompt" in kept["edits"][0]["text"]

    @pytest.mark.parametrize("dataset_name", BENCHMARK_DATASET_NAMES)
    def test_removes_common_benchmark_names(self, dataset_name: str) -> None:
        """Should remove prompts referencing common benchmark dataset names."""
        result = _sanitize_system_prompts(
            [f"Answer like in {dataset_name}.", "Be helpful."]
        )
        kept = _assert_single_prompt(result)
        assert "Be helpful" in kept["edits"][0]["text"]

    def test_preserves_clean_prompts(self) -> None:
        """Should preserve prompts without data leakage."""
        prompt_json = make_prompt_json(
            [
                [
                    system_message("You are a helpful assistant."),
                    user_message("Answer my question."),
                ],
                [system_message("Be concise and accurate.")],
            ]
        )

        result = sanitize_generated_prompts(prompt_json, "my_metric")

        assert len(result["prompts"]) == 2

    def test_sanitizes_an_edit_to_a_multimodal_text_block(self) -> None:
        payload = {
            "prompts": [
                {
                    "edits": [
                        {
                            "segment_id": "message:1:part:0",
                            "text": "Describe this image with f1 score.",
                        }
                    ]
                },
                {
                    "edits": [
                        {
                            "segment_id": "message:1:part:0",
                            "text": "What is in this image?",
                        }
                    ]
                },
            ]
        }
        result = sanitize_generated_prompts(payload, "my_metric")
        assert result["prompts"] == [payload["prompts"][1]]
        assert len(payload["prompts"]) == 2


@pytest.mark.parametrize(
    "metric_name,content",
    [
        ("exact", "Respond with the exact color name."),
        ("accuracy", "Prioritize accuracy and use only the supplied evidence."),
        ("", "Return a short answer."),
    ],
)
def test_ordinary_metric_words_do_not_reject_valid_candidates(
    metric_name: str, content: str
) -> None:
    result = _sanitize_system_prompts([content], metric_name=metric_name)
    assert len(result["prompts"]) == 1


@pytest.mark.parametrize(
    "content",
    ["Optimize for exact.", "Maximize the exact metric.", "Call exact(output)."],
)
def test_explicit_references_to_a_common_word_metric_are_rejected(
    content: str,
) -> None:
    result = _sanitize_system_prompts(
        [content, "Return a short answer."], metric_name="exact"
    )
    assert len(result["prompts"]) == 1
    assert result["prompts"][0]["edits"][0]["text"] == "Return a short answer."
