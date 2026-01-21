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
    _format_agent_prompts_for_prompt,
    AgentBundleCandidate,
    AgentMetadata,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.core import runtime
from tests.unit.algorithms.meta_prompt_optimizer._meta_prompt_test_helpers import (
    make_prompt_json,
    make_system_prompt_json,
)
from tests.unit.fixtures import system_message, user_message
from tests.unit.fixtures import role_only
from tests.unit.test_helpers import make_optimization_context

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

    def test_handles_empty_prompts_list(self) -> None:
        """Should handle empty prompts list."""
        prompt_json: dict[str, Any] = {"prompts": []}

        result = sanitize_generated_prompts(prompt_json, "my_metric")

        assert result["prompts"] == []

    def test_handles_missing_content(self) -> None:
        """Should handle prompts with missing content gracefully."""
        prompt_json = {
            "prompts": [
                {"prompt": [role_only("system")]},  # No content field
                {"prompt": [system_message("Valid.")]},
            ]
        }

        result = sanitize_generated_prompts(prompt_json, "my_metric")

        # Both should be preserved (missing content doesn't match patterns)
        assert len(result["prompts"]) == 2

    def test_returns_new_dict_not_modifying_original(self) -> None:
        """Should return a new dict, preserving original structure."""
        prompt_json = make_system_prompt_json(
            ["Contains dataset term.", "Clean prompt here."]
        )
        original_count = len(prompt_json["prompts"])

        result = sanitize_generated_prompts(prompt_json, "my_metric")

        # Result is returned (may have fewer prompts due to filtering)
        assert isinstance(result, dict)
        assert "prompts" in result
        # Original dict should still have same count (function returns new dict)
        assert len(prompt_json["prompts"]) == original_count


class TestFormatAgentPromptsForPrompt:
    """Tests for _format_agent_prompts_for_prompt function."""

    def test_formats_single_agent(self) -> None:
        """Should format a single agent prompt correctly."""
        prompts = {"main": ChatPrompt(system="Be helpful.", user="{question}")}

        result = _format_agent_prompts_for_prompt(prompts)

        assert "Agent name: main" in result
        assert "Messages:" in result
        assert "Be helpful." in result

    def test_formats_multiple_agents(self) -> None:
        """Should format multiple agent prompts with separation."""
        prompts = {
            "planner": ChatPrompt(system="Plan the task.", user="{task}"),
            "executor": ChatPrompt(system="Execute the plan.", user="{plan}"),
        }

        result = _format_agent_prompts_for_prompt(prompts)

        assert "Agent name: planner" in result
        assert "Agent name: executor" in result
        assert "Plan the task." in result
        assert "Execute the plan." in result

    def test_includes_json_formatted_messages(self) -> None:
        """Should include JSON-formatted messages."""
        prompts = {
            "agent": ChatPrompt(
                messages=[
                    system_message("System message"),
                    user_message("User message"),
                ]
            )
        }

        result = _format_agent_prompts_for_prompt(prompts)

        # Should be valid JSON in the output
        assert '"role"' in result
        assert '"content"' in result

    def test_handles_empty_dict(self) -> None:
        """Should handle empty prompts dict."""
        result = _format_agent_prompts_for_prompt({})

        assert result == ""


class TestAgentBundleCandidate:
    """Tests for AgentBundleCandidate dataclass."""

    def test_stores_prompts_and_metadata(self) -> None:
        """Should store prompts and metadata correctly."""
        prompts = {
            "agent1": ChatPrompt(system="System 1", user="{input}"),
            "agent2": ChatPrompt(system="System 2", user="{input}"),
        }
        metadata = {
            "agent1": AgentMetadata(
                improvement_focus="clarity",
                reasoning="Made it clearer",
            ),
            "agent2": AgentMetadata(
                improvement_focus="accuracy",
                reasoning="Added more detail",
            ),
        }

        candidate = AgentBundleCandidate(prompts=prompts, metadata=metadata)

        assert candidate.prompts == prompts
        assert candidate.metadata == metadata

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


class TestAgentMetadata:
    """Tests for AgentMetadata dataclass."""

    def test_stores_fields(self) -> None:
        """Should store improvement_focus and reasoning."""
        meta = AgentMetadata(
            improvement_focus="performance",
            reasoning="Optimized for speed",
        )

        assert meta.improvement_focus == "performance"
        assert meta.reasoning == "Optimized for speed"

    def test_defaults_to_none(self) -> None:
        """Fields should default to None."""
        meta = AgentMetadata()

        assert meta.improvement_focus is None
        assert meta.reasoning is None


def test_history_builder_assigns_trial_indices() -> None:
    optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    context = make_optimization_context({"main": ChatPrompt(system="s", user="u")})
    round_handle = optimizer.pre_round(context)
    runtime.record_and_post_trial(
        optimizer=optimizer,
        context=context,
        prompt_or_payload=context.prompts,
        score=0.8,
        round_handle=round_handle,
    )
    optimizer.post_round(round_handle=round_handle, context=context, best_score=0.8)
    history = optimizer.get_history_entries()
    assert history
    trials = history[0].get("trials") or []
    assert trials
    assert trials[0].get("trial_index") is not None
