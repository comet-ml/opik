# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from collections.abc import Callable
from unittest.mock import MagicMock

import pytest

from opik_optimizer import MetaPromptOptimizer
from opik_optimizer.algorithms.meta_prompt_optimizer.ops import candidate_ops
from tests.unit.fixtures import assert_baseline_early_stop, make_baseline_prompt


class TestMetaPromptOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_opik_client()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = MetaPromptOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: 0.96)
        monkeypatch.setattr(
            candidate_ops,
            "generate_agent_bundle_candidates",
            lambda **_kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95)
