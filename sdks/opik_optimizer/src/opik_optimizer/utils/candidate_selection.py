"""
Selection utilities for choosing the best candidate output when n>1.

This module centralizes pass@k selection policies so optimizers can delegate
candidate choice without carrying policy logic inline. It returns both the
chosen output and metadata needed for trace logging (scores, logprobs, index).
"""

from __future__ import annotations

from dataclasses import dataclass
import logging
from typing import Any, Protocol
from collections.abc import Callable


logger = logging.getLogger(__name__)

DEFAULT_SELECTION_POLICY = "best_by_metric"


class RandomLike(Protocol):
    def randrange(self, *args: Any, **kwargs: Any) -> int: ...


@dataclass(frozen=True)
class SelectionResult:
    """Selection outcome plus metadata for logging and debugging."""

    output: str
    policy: str
    chosen_index: int | None
    candidate_scores: list[float] | None
    candidate_logprobs: list[float] | None


def select_candidate(
    *,
    candidates: list[str],
    policy: str,
    metric: Callable[[dict[str, Any], str], Any] | None,
    dataset_item: dict[str, Any] | None,
    candidate_logprobs: list[float] | None,
    rng: RandomLike,
) -> SelectionResult:
    """
    Select a candidate according to the policy and return metadata for logging.

    Policies:
        - best_by_metric: score each candidate with the metric, pick the max.
        - first: pick index 0 (fast, deterministic, ignores metric).
        - concat: join all candidates into a single output string.
        - random: pick a random candidate (seeded via rng).
        - max_logprob: pick highest average logprob if available, else fallback.

    Args:
        candidates: Candidate outputs from the LLM.
        policy: Selection policy name (case-insensitive).
        metric: Metric function for best_by_metric scoring.
        dataset_item: Dataset row passed to the metric.
        candidate_logprobs: Optional per-candidate logprob scores.
        rng: Random generator used for random policy (seeded by caller).

    Returns:
        SelectionResult containing the chosen output and metadata for logging.
    """
    normalized_policy = str(policy or DEFAULT_SELECTION_POLICY).lower()
    if not candidates:
        return SelectionResult(
            output="",
            policy=normalized_policy,
            chosen_index=None,
            candidate_scores=None,
            candidate_logprobs=candidate_logprobs,
        )

    if normalized_policy == "concat":
        return _select_concat(
            candidates=candidates, candidate_logprobs=candidate_logprobs
        )

    if normalized_policy == "first":
        return _select_first(
            candidates=candidates, candidate_logprobs=candidate_logprobs
        )

    if normalized_policy == "random":
        return _select_random(
            candidates=candidates, candidate_logprobs=candidate_logprobs, rng=rng
        )

    if normalized_policy == "max_logprob":
        selection = _select_max_logprob(
            candidates=candidates,
            candidate_logprobs=candidate_logprobs,
            policy=normalized_policy,
        )
        if selection is not None:
            return selection
        normalized_policy = DEFAULT_SELECTION_POLICY

    if normalized_policy != DEFAULT_SELECTION_POLICY:
        logger.warning(
            "Unknown selection_policy '%s'; falling back to best_by_metric.",
            normalized_policy,
        )
        normalized_policy = DEFAULT_SELECTION_POLICY

    scored_candidates = _score_candidates(
        candidates=candidates, metric=metric, dataset_item=dataset_item
    )
    return _select_best_by_metric(
        candidates=candidates,
        scored_candidates=scored_candidates,
        candidate_logprobs=candidate_logprobs,
        policy=normalized_policy,
    )


def _select_concat(
    *, candidates: list[str], candidate_logprobs: list[float] | None
) -> SelectionResult:
    """Join all candidates into a single output string."""
    return SelectionResult(
        output="\n\n".join(str(candidate).strip() for candidate in candidates),
        policy="concat",
        chosen_index=None,
        candidate_scores=None,
        candidate_logprobs=candidate_logprobs,
    )


def _select_first(
    *, candidates: list[str], candidate_logprobs: list[float] | None
) -> SelectionResult:
    """Select the first candidate without scoring."""
    return SelectionResult(
        output=str(candidates[0]).strip(),
        policy="first",
        chosen_index=0,
        candidate_scores=None,
        candidate_logprobs=candidate_logprobs,
    )


def _select_random(
    *,
    candidates: list[str],
    candidate_logprobs: list[float] | None,
    rng: RandomLike,
) -> SelectionResult:
    """Select a random candidate using the provided RNG."""
    chosen_index = rng.randrange(len(candidates))
    return SelectionResult(
        output=str(candidates[chosen_index]).strip(),
        policy="random",
        chosen_index=chosen_index,
        candidate_scores=None,
        candidate_logprobs=candidate_logprobs,
    )


def _select_max_logprob(
    *,
    candidates: list[str],
    candidate_logprobs: list[float] | None,
    policy: str,
) -> SelectionResult | None:
    """Select by highest logprob, returning None when logprobs are unavailable."""
    if candidate_logprobs and len(candidate_logprobs) == len(candidates):
        chosen_index = max(
            range(len(candidate_logprobs)), key=candidate_logprobs.__getitem__
        )
        return SelectionResult(
            output=str(candidates[chosen_index]).strip(),
            policy=policy,
            chosen_index=chosen_index,
            candidate_scores=None,
            candidate_logprobs=candidate_logprobs,
        )
    return None


def _select_best_by_metric(
    *,
    candidates: list[str],
    scored_candidates: list[tuple[str, float]],
    candidate_logprobs: list[float] | None,
    policy: str,
) -> SelectionResult:
    """Pick the highest-scoring candidate and attach per-candidate scores."""
    chosen_index, (best_output, _) = max(
        enumerate(scored_candidates), key=lambda item: item[1][1]
    )
    candidate_scores = [score for _, score in scored_candidates]
    return SelectionResult(
        output=str(best_output).strip(),
        policy=policy,
        chosen_index=chosen_index,
        candidate_scores=candidate_scores,
        candidate_logprobs=candidate_logprobs,
    )


def _score_candidates(
    *,
    candidates: list[str],
    metric: Callable[[dict[str, Any], str], Any] | None,
    dataset_item: dict[str, Any] | None,
) -> list[tuple[str, float]]:
    if metric is None or dataset_item is None:
        return [(candidate, 0.0) for candidate in candidates]

    scored: list[tuple[str, float]] = []
    for candidate in candidates:
        try:
            metric_val = metric(dataset_item, candidate)
            if isinstance(metric_val, list):
                metric_score = max((score.value for score in metric_val), default=0.0)
            elif hasattr(metric_val, "value"):
                metric_score = float(metric_val.value)
            else:
                metric_score = float(metric_val)
        except Exception:
            metric_score = 0.0
        scored.append((candidate, metric_score))
    return scored


def extract_choice_logprob(
    choice: Any,
    *,
    aggregation: str = "mean",
    min_tokens: int = 5,
) -> float | None:
    """Extract an aggregated logprob score for a single model choice, if available."""
    logprobs = getattr(choice, "logprobs", None)
    if logprobs is None:
        return None
    content = getattr(logprobs, "content", None)
    if content is None and isinstance(logprobs, dict):
        content = logprobs.get("content") or logprobs.get("token_logprobs")
    if content is None:
        return None

    logprob_values: list[float] = []
    for item in content:
        if isinstance(item, (int, float)):
            logprob_values.append(float(item))
        elif isinstance(item, dict):
            value = item.get("logprob")
            if value is not None:
                logprob_values.append(float(value))
        else:
            value = getattr(item, "logprob", None)
            if value is not None:
                logprob_values.append(float(value))
    if len(logprob_values) < min_tokens:
        return None
    total = sum(logprob_values)
    if aggregation == "sum":
        return total
    if aggregation == "length_norm_sum":
        return total / len(logprob_values)
    return total / len(logprob_values)
