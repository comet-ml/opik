"""Tests for PromptHallOfFame pattern parsing/identification/extraction."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import (
    PromptHallOfFame,
)
from opik_optimizer.core.llm_calls import StructuredOutputParsingError
from tests.unit.algorithms.meta_prompt_optimizer._meta_prompt_test_helpers import (
    make_hof_entry,
)
from tests.unit.fixtures import system_message

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestPromptHallOfFameExtractPatterns:
    """Tests for extract_patterns method with mocked LLM."""

    def test_retries_with_strict_json_on_parse_error(self, mock_llm_call: Any) -> None:
        """Should retry with strict JSON instruction on parse errors."""
        hof = PromptHallOfFame()
        hof.add(
            make_hof_entry(
                score=0.7,
                trial=1,
                prompt_messages=[system_message("Test prompt")],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.8,
                trial=2,
                prompt_messages=[system_message("Test prompt")],
            )
        )
        hof.add(
            make_hof_entry(
                score=0.9,
                trial=3,
                prompt_messages=[system_message("Test prompt")],
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
