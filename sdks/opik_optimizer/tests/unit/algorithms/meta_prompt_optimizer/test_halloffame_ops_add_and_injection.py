"""Tests for PromptHallOfFame entry management and injection helpers."""

from __future__ import annotations

import pytest

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import (
    PromptHallOfFame,
)
from tests.unit.algorithms.meta_prompt_optimizer._meta_prompt_test_helpers import (
    make_hof_entry,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestPromptHallOfFameAdd:
    """Tests for PromptHallOfFame.add method."""

    def test_adds_entry_when_under_max_size(self) -> None:
        """Should add entry when hall of fame is not full."""
        hof = PromptHallOfFame(max_size=5)

        result = hof.add(make_hof_entry(score=0.8))

        assert result is True
        assert len(hof.entries) == 1
        assert hof.entries[0].score == 0.8

    def test_maintains_sorted_order(self) -> None:
        """Entries should be sorted by score descending."""
        hof = PromptHallOfFame(max_size=5)

        hof.add(make_hof_entry(score=0.5))
        hof.add(make_hof_entry(score=0.9))
        hof.add(make_hof_entry(score=0.7))

        scores = [e.score for e in hof.entries]
        assert scores == [0.9, 0.7, 0.5]

    def test_replaces_lowest_when_full(self) -> None:
        """Should replace lowest score when full and new score is higher."""
        hof = PromptHallOfFame(max_size=3)

        hof.add(make_hof_entry(score=0.6))
        hof.add(make_hof_entry(score=0.7))
        hof.add(make_hof_entry(score=0.8))

        # Try to add higher score
        result = hof.add(make_hof_entry(score=0.9))

        assert result is True
        assert len(hof.entries) == 3
        scores = [e.score for e in hof.entries]
        assert 0.6 not in scores  # Lowest was replaced
        assert 0.9 in scores

    def test_rejects_lower_score_when_full(self) -> None:
        """Should reject entry when full and score is not high enough."""
        hof = PromptHallOfFame(max_size=3)

        hof.add(make_hof_entry(score=0.7))
        hof.add(make_hof_entry(score=0.8))
        hof.add(make_hof_entry(score=0.9))

        # Try to add lower score
        result = hof.add(make_hof_entry(score=0.5))

        assert result is False
        assert len(hof.entries) == 3
        scores = [e.score for e in hof.entries]
        assert 0.5 not in scores


class TestPromptHallOfFameShouldExtractPatterns:
    """Tests for PromptHallOfFame.should_extract_patterns method."""

    def test_returns_false_when_not_enough_entries(self) -> None:
        """Should return False when fewer than 3 entries."""
        hof = PromptHallOfFame()
        hof.add(
            make_hof_entry(score=0.8, trial=1, prompt_messages=[], baseline_score=0.8)
        )
        hof.add(
            make_hof_entry(score=0.9, trial=2, prompt_messages=[], baseline_score=0.9)
        )

        assert hof.should_extract_patterns(10) is False

    def test_returns_false_when_interval_not_reached(self) -> None:
        """Should return False when interval not reached."""
        hof = PromptHallOfFame(pattern_extraction_interval=10)
        hof.add(
            make_hof_entry(score=0.7, trial=1, prompt_messages=[], baseline_score=0.7)
        )
        hof.add(
            make_hof_entry(score=0.8, trial=2, prompt_messages=[], baseline_score=0.8)
        )
        hof.add(
            make_hof_entry(score=0.9, trial=3, prompt_messages=[], baseline_score=0.9)
        )

        assert hof.should_extract_patterns(5) is False

    def test_returns_true_when_conditions_met(self) -> None:
        """Should return True when enough entries and interval reached."""
        hof = PromptHallOfFame(pattern_extraction_interval=5)
        hof.add(
            make_hof_entry(score=0.7, trial=1, prompt_messages=[], baseline_score=0.7)
        )
        hof.add(
            make_hof_entry(score=0.8, trial=2, prompt_messages=[], baseline_score=0.8)
        )
        hof.add(
            make_hof_entry(score=0.9, trial=3, prompt_messages=[], baseline_score=0.9)
        )

        assert hof.should_extract_patterns(6) is True


class TestPromptHallOfFameGetPatternsForInjection:
    """Tests for PromptHallOfFame.get_patterns_for_injection method."""

    def test_returns_empty_when_no_patterns(self) -> None:
        """Should return empty list when no patterns extracted."""
        hof = PromptHallOfFame()

        result = hof.get_patterns_for_injection(3)

        assert result == []

    def test_returns_top_n_patterns(self) -> None:
        """Should return top N patterns based on scores."""
        hof = PromptHallOfFame()

        # Add entries with patterns
        hof.entries.append(
            make_hof_entry(
                score=0.9,
                prompt_messages=[],
                baseline_score=0.9,
                extracted_patterns=["pattern_a"],
            )
        )
        hof.entries.append(
            make_hof_entry(
                score=0.8,
                prompt_messages=[],
                baseline_score=0.8,
                extracted_patterns=["pattern_b"],
            )
        )
        hof.entries.append(
            make_hof_entry(
                score=0.7,
                prompt_messages=[],
                baseline_score=0.7,
                extracted_patterns=["pattern_c"],
            )
        )

        # Add patterns to extracted_patterns
        hof.extracted_patterns = ["pattern_a", "pattern_b", "pattern_c"]

        result = hof.get_patterns_for_injection(2)

        assert len(result) == 2
        # Higher scored patterns should be first
        assert "pattern_a" in result

    def test_increments_usage_count(self) -> None:
        """Should increment usage count for returned patterns."""
        hof = PromptHallOfFame()
        hof.entries.append(
            make_hof_entry(
                score=0.9,
                prompt_messages=[],
                baseline_score=0.9,
                extracted_patterns=["pattern_x"],
            )
        )
        hof.extracted_patterns = ["pattern_x"]

        initial_count = hof.pattern_usage_count["pattern_x"]
        hof.get_patterns_for_injection(1)
        new_count = hof.pattern_usage_count["pattern_x"]

        assert new_count == initial_count + 1
