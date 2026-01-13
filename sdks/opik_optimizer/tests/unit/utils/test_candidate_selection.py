from __future__ import annotations

import random

from opik_optimizer.utils.candidate_selection import select_candidate


def test_select_candidate_best_by_metric() -> None:
    def metric(dataset_item, llm_output):
        _ = dataset_item
        return 1.0 if llm_output == "good" else 0.0

    result = select_candidate(
        candidates=["bad", "good"],
        policy="best_by_metric",
        metric=metric,
        dataset_item={"id": "1"},
        candidate_logprobs=None,
        rng=random.Random(0),
    )

    assert result.output == "good"
    assert result.policy == "best_by_metric"
    assert result.chosen_index == 1
    assert result.candidate_scores == [0.0, 1.0]


def test_select_candidate_concat() -> None:
    result = select_candidate(
        candidates=["a", "b"],
        policy="concat",
        metric=None,
        dataset_item=None,
        candidate_logprobs=None,
        rng=random.Random(0),
    )

    assert result.output == "a\n\nb"
    assert result.chosen_index is None


def test_select_candidate_max_logprob_falls_back() -> None:
    def metric(dataset_item, llm_output):
        _ = dataset_item
        return 1.0 if llm_output == "good" else 0.0

    result = select_candidate(
        candidates=["bad", "good"],
        policy="max_logprob",
        metric=metric,
        dataset_item={"id": "1"},
        candidate_logprobs=None,
        rng=random.Random(0),
    )

    assert result.output == "good"
    assert result.policy == "best_by_metric"
