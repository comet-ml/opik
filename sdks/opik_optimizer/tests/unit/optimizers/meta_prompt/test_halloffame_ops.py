"""
Unit tests for opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops module.

Tests cover:
- HallOfFameEntry: Dataclass behavior
- PromptHallOfFame: Entry management, pattern extraction, pattern injection
"""

import pytest
from unittest.mock import MagicMock, patch
from typing import Any

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import (
    HallOfFameEntry,
    PromptHallOfFame,
)


class TestHallOfFameEntry:
    """Tests for HallOfFameEntry dataclass."""

    def test_stores_required_fields(self) -> None:
        """Should store all required fields correctly."""
        messages = [
            {"role": "system", "content": "Be helpful."},
            {"role": "user", "content": "{question}"},
        ]

        entry = HallOfFameEntry(
            prompt_messages=messages,
            score=0.85,
            trial_number=5,
            improvement_over_baseline=0.15,
            metric_name="accuracy",
        )

        assert entry.prompt_messages == messages
        assert entry.score == 0.85
        assert entry.trial_number == 5
        assert entry.improvement_over_baseline == 0.15
        assert entry.metric_name == "accuracy"

    def test_optional_fields_default_to_none_or_empty(self) -> None:
        """Optional fields should have proper defaults."""
        entry = HallOfFameEntry(
            prompt_messages=[],
            score=0.5,
            trial_number=1,
            improvement_over_baseline=0.0,
            metric_name="test",
        )

        assert entry.extracted_patterns is None
        assert entry.metadata == {}

    def test_optional_fields_can_be_set(self) -> None:
        """Optional fields should be settable."""
        entry = HallOfFameEntry(
            prompt_messages=[],
            score=0.5,
            trial_number=1,
            improvement_over_baseline=0.0,
            metric_name="test",
            extracted_patterns=["pattern1", "pattern2"],
            metadata={"key": "value"},
        )

        assert entry.extracted_patterns == ["pattern1", "pattern2"]
        assert entry.metadata == {"key": "value"}


class TestPromptHallOfFame:
    """Tests for PromptHallOfFame class."""

    def test_initialization(self) -> None:
        """Should initialize with correct defaults."""
        hof = PromptHallOfFame()

        assert hof.max_size == 10
        assert hof.pattern_extraction_interval == 5
        assert hof.entries == []
        assert hof.extracted_patterns == []

    def test_custom_initialization(self) -> None:
        """Should accept custom parameters."""
        hof = PromptHallOfFame(max_size=5, pattern_extraction_interval=3)

        assert hof.max_size == 5
        assert hof.pattern_extraction_interval == 3


class TestPromptHallOfFameAdd:
    """Tests for PromptHallOfFame.add method."""

    def _create_entry(self, score: float, trial: int = 1) -> HallOfFameEntry:
        """Helper to create test entries."""
        return HallOfFameEntry(
            prompt_messages=[{"role": "system", "content": f"Score {score}"}],
            score=score,
            trial_number=trial,
            improvement_over_baseline=score - 0.5,
            metric_name="accuracy",
        )

    def test_adds_entry_when_under_max_size(self) -> None:
        """Should add entry when hall of fame is not full."""
        hof = PromptHallOfFame(max_size=5)

        result = hof.add(self._create_entry(0.8))

        assert result is True
        assert len(hof.entries) == 1
        assert hof.entries[0].score == 0.8

    def test_maintains_sorted_order(self) -> None:
        """Entries should be sorted by score descending."""
        hof = PromptHallOfFame(max_size=5)

        hof.add(self._create_entry(0.5))
        hof.add(self._create_entry(0.9))
        hof.add(self._create_entry(0.7))

        scores = [e.score for e in hof.entries]
        assert scores == [0.9, 0.7, 0.5]

    def test_replaces_lowest_when_full(self) -> None:
        """Should replace lowest score when full and new score is higher."""
        hof = PromptHallOfFame(max_size=3)

        hof.add(self._create_entry(0.6))
        hof.add(self._create_entry(0.7))
        hof.add(self._create_entry(0.8))

        # Try to add higher score
        result = hof.add(self._create_entry(0.9))

        assert result is True
        assert len(hof.entries) == 3
        scores = [e.score for e in hof.entries]
        assert 0.6 not in scores  # Lowest was replaced
        assert 0.9 in scores

    def test_rejects_lower_score_when_full(self) -> None:
        """Should reject entry when full and score is not high enough."""
        hof = PromptHallOfFame(max_size=3)

        hof.add(self._create_entry(0.7))
        hof.add(self._create_entry(0.8))
        hof.add(self._create_entry(0.9))

        # Try to add lower score
        result = hof.add(self._create_entry(0.5))

        assert result is False
        assert len(hof.entries) == 3
        scores = [e.score for e in hof.entries]
        assert 0.5 not in scores


class TestPromptHallOfFameShouldExtractPatterns:
    """Tests for PromptHallOfFame.should_extract_patterns method."""

    def _create_entry(self, score: float, trial: int) -> HallOfFameEntry:
        return HallOfFameEntry(
            prompt_messages=[],
            score=score,
            trial_number=trial,
            improvement_over_baseline=0.0,
            metric_name="accuracy",
        )

    def test_returns_false_when_not_enough_entries(self) -> None:
        """Should return False when fewer than 3 entries."""
        hof = PromptHallOfFame()
        hof.add(self._create_entry(0.8, 1))
        hof.add(self._create_entry(0.9, 2))

        assert hof.should_extract_patterns(10) is False

    def test_returns_false_when_interval_not_reached(self) -> None:
        """Should return False when interval not reached."""
        hof = PromptHallOfFame(pattern_extraction_interval=10)
        hof.add(self._create_entry(0.7, 1))
        hof.add(self._create_entry(0.8, 2))
        hof.add(self._create_entry(0.9, 3))

        assert hof.should_extract_patterns(5) is False

    def test_returns_true_when_conditions_met(self) -> None:
        """Should return True when enough entries and interval reached."""
        hof = PromptHallOfFame(pattern_extraction_interval=5)
        hof.add(self._create_entry(0.7, 1))
        hof.add(self._create_entry(0.8, 2))
        hof.add(self._create_entry(0.9, 3))

        assert hof.should_extract_patterns(6) is True


class TestPromptHallOfFameGetPatternsForInjection:
    """Tests for PromptHallOfFame.get_patterns_for_injection method."""

    def _create_entry_with_patterns(
        self, score: float, patterns: list[str]
    ) -> HallOfFameEntry:
        return HallOfFameEntry(
            prompt_messages=[],
            score=score,
            trial_number=1,
            improvement_over_baseline=0.0,
            metric_name="accuracy",
            extracted_patterns=patterns,
        )

    def test_returns_empty_when_no_patterns(self) -> None:
        """Should return empty list when no patterns extracted."""
        hof = PromptHallOfFame()

        result = hof.get_patterns_for_injection(3)

        assert result == []

    def test_returns_top_n_patterns(self) -> None:
        """Should return top N patterns based on scores."""
        hof = PromptHallOfFame()

        # Add entries with patterns
        hof.entries.append(self._create_entry_with_patterns(0.9, ["pattern_a"]))
        hof.entries.append(self._create_entry_with_patterns(0.8, ["pattern_b"]))
        hof.entries.append(self._create_entry_with_patterns(0.7, ["pattern_c"]))

        # Add patterns to extracted_patterns
        hof.extracted_patterns = ["pattern_a", "pattern_b", "pattern_c"]

        result = hof.get_patterns_for_injection(2)

        assert len(result) == 2
        # Higher scored patterns should be first
        assert "pattern_a" in result

    def test_increments_usage_count(self) -> None:
        """Should increment usage count for returned patterns."""
        hof = PromptHallOfFame()
        hof.entries.append(self._create_entry_with_patterns(0.9, ["pattern_x"]))
        hof.extracted_patterns = ["pattern_x"]

        initial_count = hof.pattern_usage_count["pattern_x"]
        hof.get_patterns_for_injection(1)
        new_count = hof.pattern_usage_count["pattern_x"]

        assert new_count == initial_count + 1


class TestPromptHallOfFameParsePatternResponse:
    """Tests for _parse_pattern_response method."""

    def test_parses_json_format(self) -> None:
        """Should parse JSON-formatted pattern response."""
        hof = PromptHallOfFame()

        response = '{"patterns": [{"pattern": "Be concise", "example": "Short answers"}]}'

        result = hof._parse_pattern_response(response)

        assert len(result) == 1
        assert "Be concise" in result[0]
        assert "Short answers" in result[0]

    def test_parses_string_list_format(self) -> None:
        """Should handle patterns as string list."""
        hof = PromptHallOfFame()

        response = '{"patterns": ["Pattern 1", "Pattern 2"]}'

        result = hof._parse_pattern_response(response)

        assert result == ["Pattern 1", "Pattern 2"]

    def test_fallback_to_bullet_extraction(self) -> None:
        """Should extract bullet points when JSON parsing fails."""
        hof = PromptHallOfFame()

        response = """
        - First pattern that is important
        - Second pattern with details
        - Third pattern example
        """

        result = hof._parse_pattern_response(response)

        assert len(result) >= 1
        # Should extract the text after bullet points

    def test_limits_to_five_patterns(self) -> None:
        """Should limit extracted patterns to 5."""
        hof = PromptHallOfFame()

        response = '{"patterns": ["p1", "p2", "p3", "p4", "p5", "p6", "p7"]}'

        result = hof._parse_pattern_response(response)

        assert len(result) <= 5


class TestPromptHallOfFameIdentifyPatternsInPrompt:
    """Tests for _identify_patterns_in_prompt method."""

    def test_identifies_matching_patterns(self) -> None:
        """Should identify patterns that appear in prompt."""
        hof = PromptHallOfFame()

        prompt_messages = [
            {"role": "system", "content": "Be concise and helpful."}
        ]
        patterns = [
            "Be concise | Example: short answers",
            "Use examples | Example: show demonstrations",
        ]

        result = hof._identify_patterns_in_prompt(prompt_messages, patterns)

        assert "Be concise | Example: short answers" in result

    def test_returns_empty_when_no_matches(self) -> None:
        """Should return empty list when no patterns match."""
        hof = PromptHallOfFame()

        # Use very specific patterns that won't match "Simple prompt."
        prompt_messages = [{"role": "system", "content": "Simple prompt."}]
        patterns = ["xyzzy_unique_pattern", "qwerty_another_unique"]

        result = hof._identify_patterns_in_prompt(prompt_messages, patterns)

        # These patterns have no keywords that appear in "Simple prompt."
        assert result == []

    def test_case_insensitive_matching(self) -> None:
        """Should match patterns case-insensitively."""
        hof = PromptHallOfFame()

        prompt_messages = [{"role": "system", "content": "BE HELPFUL AND CONCISE"}]
        patterns = ["be helpful"]

        result = hof._identify_patterns_in_prompt(prompt_messages, patterns)

        assert "be helpful" in result


class TestPromptHallOfFameExtractPatterns:
    """Tests for extract_patterns method with mocked LLM."""

    def _create_entry(self, score: float, trial: int) -> HallOfFameEntry:
        return HallOfFameEntry(
            prompt_messages=[{"role": "system", "content": "Test prompt"}],
            score=score,
            trial_number=trial,
            improvement_over_baseline=score - 0.5,
            metric_name="accuracy",
        )

    def test_returns_empty_when_not_enough_entries(self) -> None:
        """Should return empty list when fewer than 3 entries."""
        hof = PromptHallOfFame()
        hof.add(self._create_entry(0.8, 1))
        hof.add(self._create_entry(0.9, 2))

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert result == []

    def test_calls_llm_and_stores_patterns(self, mock_llm_call) -> None:
        """Should call LLM and store extracted patterns."""
        hof = PromptHallOfFame()
        hof.add(self._create_entry(0.7, 1))
        hof.add(self._create_entry(0.8, 2))
        hof.add(self._create_entry(0.9, 3))

        mock_llm_call('{"patterns": ["Pattern A", "Pattern B"]}')

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert len(result) == 2
        assert "Pattern A" in result
        assert "Pattern B" in result
        assert len(hof.extracted_patterns) >= 2

    def test_handles_llm_error_gracefully(self, mock_llm_call) -> None:
        """Should handle LLM errors gracefully."""
        hof = PromptHallOfFame()
        hof.add(self._create_entry(0.7, 1))
        hof.add(self._create_entry(0.8, 2))
        hof.add(self._create_entry(0.9, 3))

        mock_llm_call(raises=Exception("LLM error"))

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert result == []

