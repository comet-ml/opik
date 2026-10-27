"""MetaPrompt learns from its best prompts across rounds."""

from __future__ import annotations

import json
from typing import Any

import pytest

from opik_optimizer.core import llm_calls
from tests.unit.characterization import harness


def test_patterns_from_the_best_prompts_feed_later_generations(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    fake = install_fakes(scenario)
    requests: list[str] = []
    original = llm_calls.call_model

    def recording(**kwargs: Any) -> Any:
        requests.append(json.dumps(kwargs.get("messages"), default=str))
        return original(**kwargs)

    monkeypatch.setattr(llm_calls, "call_model", recording)
    optimizer = harness.make_optimizer("MetaPromptOptimizer")
    optimizer.pattern_extraction_interval = 1
    optimizer.pattern_injection_rate = 1.0
    optimizer.perfect_score = 2.0  # keep searching so later rounds run

    result = harness.run_optimizer(
        "MetaPromptOptimizer", scenario, optimizer=optimizer, max_trials=12
    )

    assert "PatternExtractionResponse" in fake.calls
    extraction = fake.calls.index("PatternExtractionResponse")
    later = requests[extraction + 1 :]
    # The fake extracts "State the exact value"; later generations must see it.
    assert any("State the exact value" in request for request in later)
    assert result.score > result.initial_score
