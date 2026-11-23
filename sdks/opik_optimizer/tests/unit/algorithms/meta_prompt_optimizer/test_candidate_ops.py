"""
Unit tests for opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops module.

Tests cover:
- sanitize_generated_prompts: Data leakage detection and removal
- _format_agent_prompts_for_prompt: Agent prompt formatting
- AgentBundleCandidate: Dataclass behavior
"""

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops import (
    sanitize_generated_prompts,
    AgentBundleCandidate,
    AgentMetadata,
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
        assert "accuracy_score" not in kept["prompt"][0]["content"]

    @pytest.mark.parametrize("pattern", METRIC_LEAKAGE_PATTERNS)
    def test_removes_metric_patterns(self, pattern: str) -> None:
        """Should remove prompts containing common metric patterns."""
        result = _sanitize_system_prompts([f"Optimize for {pattern}.", "Clean prompt."])
        kept = _assert_single_prompt(result)
        assert "Clean prompt" in kept["prompt"][0]["content"]

    @pytest.mark.parametrize("pattern", DATASET_LEAKAGE_PATTERNS)
    def test_removes_dataset_patterns(self, pattern: str) -> None:
        """Should remove prompts containing dataset-specific patterns."""
        result = _sanitize_system_prompts([f"Use the {pattern}.", "Clean prompt."])
        kept = _assert_single_prompt(result)
        assert "Clean prompt" in kept["prompt"][0]["content"]

    @pytest.mark.parametrize("content", EVALUATION_LEAKAGE_PATTERNS)
    def test_removes_evaluation_terms(self, content: str) -> None:
        """Should remove prompts referencing evaluation/scoring terms."""
        result = _sanitize_system_prompts(
            [content, "Safe prompt."], metric_name="test_metric"
        )
        kept = _assert_single_prompt(result)
        assert "Safe prompt" in kept["prompt"][0]["content"]

    @pytest.mark.parametrize("dataset_name", BENCHMARK_DATASET_NAMES)
    def test_removes_common_benchmark_names(self, dataset_name: str) -> None:
        """Should remove prompts referencing common benchmark dataset names."""
        result = _sanitize_system_prompts(
            [f"Answer like in {dataset_name}.", "Be helpful."]
        )
        kept = _assert_single_prompt(result)
        assert "Be helpful" in kept["prompt"][0]["content"]

    def test_case_insensitive_matching(self) -> None:
        """Should match patterns case-insensitively."""
        result = _sanitize_system_prompts(
            ["Optimize for F1 SCORE metric.", "Good prompt."],
            metric_name="other_metric",
        )
        assert len(result["prompts"]) == 1

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

    def test_handles_multimodal_content(self) -> None:
        """Should handle multimodal content (list of content parts) without errors."""
        # Create a prompt with multimodal content (text + image)
        prompt_json = {
            "prompts": [
                {
                    "prompt": [
                        system_message("You are an image analysis assistant."),
                        user_message(
                            [
                                {
                                    "type": "text",
                                    "text": "Describe this image with f1 score.",
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "https://example.com/image.jpg"
                                    },
                                },
                            ]
                        ),
                    ]
                },
                {
                    "prompt": [
                        system_message("Be helpful."),
                        user_message(
                            [
                                {"type": "text", "text": "What is in this image?"},
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "https://example.com/image2.jpg"
                                    },
                                },
                            ]
                        ),
                    ]
                },
            ]
        }

        result = sanitize_generated_prompts(prompt_json, "my_metric")

        # First prompt should be rejected (contains "f1 score"), second should be kept
        assert len(result["prompts"]) == 1
        kept = result["prompts"][0]
        # Verify multimodal structure is preserved
        user_msg = kept["prompt"][1]
        assert isinstance(user_msg["content"], list)
        assert len(user_msg["content"]) == 2
        assert user_msg["content"][0]["type"] == "text"
        assert "What is in this image?" in user_msg["content"][0]["text"]

    def test_handles_multimodal_content_with_clean_text(self) -> None:
        """Should preserve multimodal prompts with clean text content."""
        prompt_json = {
            "prompts": [
                {
                    "prompt": [
                        system_message("Analyze images."),
                        user_message(
                            [
                                {"type": "text", "text": "What do you see?"},
                                {"type": "image_url", "image_url": {"url": "{image}"}},
                            ]
                        ),
                    ]
                },
            ]
        }

        result = sanitize_generated_prompts(prompt_json, "my_metric")

        # Should be preserved (no leakage)
        assert len(result["prompts"]) == 1
        kept = result["prompts"][0]
        assert isinstance(kept["prompt"][1]["content"], list)


class TestAgentBundleCandidate:
    """Tests for AgentBundleCandidate dataclass."""

    def test_get_agent_names(self) -> None:
        """get_agent_names should return all agent names."""
        prompts = {
            "planner": ChatPrompt(system="Plan", user="{task}"),
            "executor": ChatPrompt(system="Execute", user="{plan}"),
            "reviewer": ChatPrompt(system="Review", user="{result}"),
        }

        candidate = AgentBundleCandidate(prompts=prompts, metadata={})

        names = candidate.get_agent_names()

        assert set(names) == {"planner", "executor", "reviewer"}

    def test_get_agent_reasoning(self) -> None:
        """get_agent_reasoning should return reasoning for specific agent."""
        metadata = {
            "agent1": AgentMetadata(reasoning="Reasoning for agent1"),
        }

        candidate = AgentBundleCandidate(
            prompts={"agent1": ChatPrompt(system="Test", user="{x}")},
            metadata=metadata,
        )

        assert candidate.get_agent_reasoning("agent1") == "Reasoning for agent1"
        assert candidate.get_agent_reasoning("unknown") is None

    def test_get_agent_improvement_focus(self) -> None:
        """get_agent_improvement_focus should return focus for specific agent."""
        metadata = {
            "agent1": AgentMetadata(improvement_focus="clarity"),
        }

        candidate = AgentBundleCandidate(
            prompts={"agent1": ChatPrompt(system="Test", user="{x}")},
            metadata=metadata,
        )

        assert candidate.get_agent_improvement_focus("agent1") == "clarity"
        assert candidate.get_agent_improvement_focus("unknown") is None
