"""Tests for HallOfFameEntry + PromptHallOfFame initialization."""

from __future__ import annotations

import pytest

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import (
    HallOfFameEntry,
    PromptHallOfFame,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


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

