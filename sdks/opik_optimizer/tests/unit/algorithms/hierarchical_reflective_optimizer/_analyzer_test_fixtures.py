"""Shared fixtures for HierarchicalRootCauseAnalyzer unit tests."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.utils.prompt_library import PromptLibrary


@pytest.fixture
def mock_prompts() -> PromptLibrary:
    """Create mock prompt library for tests."""
    defaults = {
        "batch_analysis_prompt": "Analyze batch: {formatted_batch}",
        "synthesis_prompt": "Synthesize: {batch_summaries}",
        "improve_prompt_template": "Improve: {prompts_section}",
    }
    return PromptLibrary(defaults)


@pytest.fixture
def analyzer() -> HierarchicalRootCauseAnalyzer:
    """Create analyzer instance for tests."""
    prompts = PromptLibrary(
        {
            "batch_analysis_prompt": "Analyze batch: {formatted_batch}",
            "synthesis_prompt": "Synthesize: {batch_summaries}",
            "improve_prompt_template": "Improve: {prompts_section}",
        }
    )
    return HierarchicalRootCauseAnalyzer(
        reasoning_model="gpt-4o",
        seed=42,
        max_parallel_batches=2,
        batch_size=5,
        model_parameters={"temperature": 0.1},
        prompts=prompts,
        verbose=0,
    )


@pytest.fixture
def mock_evaluation_result() -> MagicMock:
    """Create a mock evaluation result with test cases."""
    mock = MagicMock()

    # Create test results with scores and reasons
    test_results = []
    for i in range(10):
        test_result = MagicMock()
        test_result.trial_id = f"trial_{i}"
        test_result.test_case = MagicMock()
        test_result.test_case.dataset_item_id = f"item_{i}"

        # Add score with reason
        score = MagicMock()
        score.name = "accuracy"
        score.value = 0.5 if i % 2 == 0 else 0.9
        score.reason = (
            f"Test case {i} failed due to parsing error" if i % 2 == 0 else "Correct"
        )
        score.scoring_failed = False
        test_result.score_results = [score]

        test_results.append(test_result)

    mock.test_results = test_results
    return mock
