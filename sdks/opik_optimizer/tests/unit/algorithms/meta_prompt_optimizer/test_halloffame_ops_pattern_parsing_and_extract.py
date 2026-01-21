"""Tests for PromptHallOfFame pattern parsing/identification/extraction."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import PromptHallOfFame
from opik_optimizer.core.llm_calls import StructuredOutputParsingError
from tests.unit.algorithms.meta_prompt_optimizer._meta_prompt_test_helpers import (
    make_hof_entry,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestPromptHallOfFameParsePatternResponse:
    """Tests for _parse_pattern_response method."""

    def test_parses_json_format(self) -> None:
        """Should parse JSON-formatted pattern response."""
        hof = PromptHallOfFame()

        response = (
            '{"patterns": [{"pattern": "Be concise", "example": "Short answers"}]}'
        )

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

        prompt_messages = [{"role": "system", "content": "Be concise and helpful."}]
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

    def test_returns_empty_when_not_enough_entries(self) -> None:
        """Should return empty list when fewer than 3 entries."""
        hof = PromptHallOfFame()
        hof.add(
            make_hof_entry(
                score=0.8,
                trial=1,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.9,
                trial=2,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert result == []

    def test_calls_llm_and_stores_patterns(self, mock_llm_call: Any) -> None:
        """Should call LLM and store extracted patterns."""
        hof = PromptHallOfFame()
        hof.add(
            make_hof_entry(
                score=0.7,
                trial=1,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.8,
                trial=2,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.9,
                trial=3,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )

        mock_llm_call('{"patterns": ["Pattern A", "Pattern B"]}')

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert len(result) == 2
        assert "Pattern A" in result
        assert "Pattern B" in result
        assert len(hof.extracted_patterns) >= 2

    def test_handles_llm_error_gracefully(self, mock_llm_call: Any) -> None:
        """Should handle LLM errors gracefully."""
        hof = PromptHallOfFame()
        hof.add(
            make_hof_entry(
                score=0.7,
                trial=1,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.8,
                trial=2,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.9,
                trial=3,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )

        mock_llm_call(raises=Exception("LLM error"))

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert result == []

    def test_retries_with_strict_json_on_parse_error(self, mock_llm_call: Any) -> None:
        """Should retry with strict JSON instruction on parse errors."""
        hof = PromptHallOfFame()
        hof.add(
            make_hof_entry(
                score=0.7,
                trial=1,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.8,
                trial=2,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.9,
                trial=3,
                prompt_messages=[{"role": "system", "content": "Test prompt"}],
            )
        )

        calls: list[dict[str, Any]] = []

        def side_effect(**kwargs: Any) -> Any:
            calls.append(kwargs)
            if len(calls) == 1:
                raise StructuredOutputParsingError("bad", ValueError("bad"))
            return {"patterns": ["Pattern A"]}

        mock_llm_call(side_effect=side_effect)

        result = hof.extract_patterns("gpt-4", {}, "accuracy")

        assert "Pattern A" in result
        assert len(calls) == 2
        assert "Return ONLY valid JSON" in calls[1]["messages"][1]["content"]

