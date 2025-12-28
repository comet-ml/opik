"""
ARC-AGI metric helpers, registry, and canonical evaluation defaults.

The HRPO pipeline optimizes Python programs that solve ARC puzzles.  Relying on
a single scalar obscures *why* a candidate failed, so we collect multiple
signals that describe different notions of success:

``arc_agi2_exact``
    Strict, pass@1-like accuracy.  Equals 1.0 only when every output grid
    matches exactly.  This is the primary metric reported to Opik.

``arc_agi2_approx_match``
    Fraction of matching cells between predicted and gold grids; aka “likeness”.
    Serves as a shaped reward so HRPO can learn even before landing on an exact
    solution.  It discourages degenerate strategies such as outputting all
    zeros or copying the input grid.

``arc_agi2_label_iou``
    Average per-label intersection-over-union.  Measures whether the correct
    color blobs appear in roughly the right locations—useful for rules that are
    structurally right but slightly misaligned.

``arc_agi2_foreground_match``
    Likeness computed only over the foreground (all cells whose gold value is
    not the dominant background color).  This removes large blankets of
    whitespace from the equation so geometry/palette mistakes count more.

The optimizer typically samples *k* candidate programs and evaluates each one.
We default to ``pass@k = 2`` to mirror Poetiq’s harness and to keep evaluation
costs low while still providing a notion of retry budget.  Keep ``DEFAULT_PASS_AT_K``
in sync with :class:`EvaluationConfig` to ensure the scoring reason text matches
the reward weighting.

:func:`build_multi_metric_objective` wires all of the above into
:class:`opik_optimizer.MultiMetricObjective` so callers can simply request
``DEFAULT_METRIC_SEQUENCE``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Callable, Iterable

import numpy as np
from opik.evaluation.metrics import score_result
from opik_optimizer import MultiMetricObjective


def approx_match_score(pred: np.ndarray, truth: np.ndarray) -> float:
    """
    Compute per-pixel likeness for equally sized grids.

    ARC puzzles often have failure cases where the candidate solution overlaps
    the ground truth but differs in a few cells.  Returning the fraction of
    matching pixels lets us provide dense feedback to HRPO even when the exact
    metric is zero.
    """
    if pred.shape != truth.shape:
        return 0.0
    if truth.size == 0:
        return 1.0
    return float(np.mean(pred == truth))


def label_iou(pred: np.ndarray, truth: np.ndarray) -> float:
    """
    Average per-label intersection-over-union.

    ARC puzzles frequently rely on remembering which color blob represents a
    concept.  IoU captures “did you put the red blob roughly where it belongs”
    even if the blob is slightly misaligned.  This metric complements likeness:
    likeness punishes all pixel mistakes equally, while IoU focuses on semantic
    regions.
    """
    if pred.shape != truth.shape or truth.size == 0:
        return 0.0
    labels = np.unique(truth)
    if labels.size == 0:
        return 0.0
    ious = []
    for label in labels:
        pred_mask = pred == label
        truth_mask = truth == label
        union = np.logical_or(pred_mask, truth_mask).sum()
        if union == 0:
            continue
        inter = np.logical_and(pred_mask, truth_mask).sum()
        ious.append(inter / union)
    if not ious:
        return 0.0
    return float(np.mean(ious))


def foreground_match_score(
    pred: np.ndarray,
    truth: np.ndarray,
    foreground_values: set[int] | None = None,
) -> float:
    """
    Compute likeness while ignoring background cells.

    ``foreground_values`` can be provided to explicitly specify which gold
    colors count as signal.  When omitted we fall back to ignoring the dominant
    background color per grid (same behavior as before).
    """
    if pred.shape != truth.shape:
        return 0.0
    flat_truth = truth.flatten()
    if flat_truth.size == 0:
        return 1.0
    if foreground_values:
        mask = np.isin(truth, list(foreground_values))
    else:
        values, counts = np.unique(flat_truth, return_counts=True)
        background = values[np.argmax(counts)]
        mask = truth != background
    if not mask.any():
        return 1.0
    return float(np.mean(pred[mask] == truth[mask]))


@dataclass(frozen=True)
class MetricDefinition:
    """
    Describes a scalar metric used inside the multi-metric objective.

    Attributes
    ----------
    name:
        Identifier passed to Opik.  The same name must exist in the payload
        returned by :func:`evaluate_arc_response`.
    extractor:
        Callable that consumes the evaluator payload and returns a float.  This
        isolates each metric from the exact shape of the evaluation cache.
    weight:
        Relative contribution when the metric participates in the multi-metric
        objective.  We keep weights here so callers can simply ask for the
        metric by name and still obtain the correct weighting.
    """

    name: str
    extractor: Callable[[dict[str, Any]], float]
    weight: float = 1.0


def _extract(name: str) -> Callable[[dict[str, Any]], float]:
    return lambda data: data["metrics"][name]


METRIC_DEFINITIONS: dict[str, MetricDefinition] = {
    "arc_agi2_accuracy": MetricDefinition(
        name="arc_agi2_accuracy",
        extractor=lambda data: data["composite_value"],
        weight=1.0,
    ),
    "arc_agi2_exact": MetricDefinition(
        name="arc_agi2_exact", extractor=_extract("arc_agi2_exact"), weight=1.0
    ),
    "arc_agi2_approx_match": MetricDefinition(
        name="arc_agi2_approx_match",
        extractor=_extract("arc_agi2_approx_match"),
        weight=0.2,
    ),
    "arc_agi2_label_iou": MetricDefinition(
        name="arc_agi2_label_iou",
        extractor=_extract("arc_agi2_label_iou"),
        weight=0.5,
    ),
    "arc_agi2_foreground_match": MetricDefinition(
        name="arc_agi2_foreground_match",
        extractor=_extract("arc_agi2_foreground_match"),
        weight=0.4,
    ),
}

# Canonical evaluation knobs shared by the HRPO entry point.
DEFAULT_METRIC_SEQUENCE: tuple[str, ...] = (
    "arc_agi2_exact",
    "arc_agi2_approx_match",
    "arc_agi2_label_iou",
    "arc_agi2_foreground_match",
)
DEFAULT_PASS_AT_K: int = 2
LIKENESS_REWARD_WEIGHT: float = METRIC_DEFINITIONS["arc_agi2_approx_match"].weight
LABEL_IOU_REWARD_WEIGHT: float = METRIC_DEFINITIONS["arc_agi2_label_iou"].weight
FOREGROUND_REWARD_WEIGHT: float = METRIC_DEFINITIONS["arc_agi2_foreground_match"].weight


def get_metric_definition(name: str) -> MetricDefinition:
    return METRIC_DEFINITIONS[name]


def build_metric_function(
    definition: MetricDefinition,
    evaluation_fn: Callable[[dict[str, Any], str], dict[str, Any]],
    handle_exception: Callable[[str, Exception], score_result.ScoreResult],
) -> Callable[[dict[str, Any], str], score_result.ScoreResult]:
    """Create a score_result-producing function for MultiMetricObjective."""

    def metric(
        dataset_item: dict[str, Any], llm_output: str
    ) -> score_result.ScoreResult:
        try:
            data = evaluation_fn(dataset_item, llm_output)
        except Exception as exc:  # pragma: no cover - delegated upstream
            return handle_exception(definition.name, exc)
        return score_result.ScoreResult(
            name=definition.name,
            value=definition.extractor(data),
            scoring_failed=False,
            reason=data.get("reason", ""),
            metadata=data.get("metadata"),
        )

    return metric


def build_metric_functions(
    names: Iterable[str],
    evaluation_fn: Callable[[dict[str, Any], str], dict[str, Any]],
    handle_exception: Callable[[str, Exception], score_result.ScoreResult],
) -> list[Callable[[dict[str, Any], str], score_result.ScoreResult]]:
    return [
        build_metric_function(
            get_metric_definition(name), evaluation_fn, handle_exception
        )
        for name in names
    ]


def normalized_weights(names: Iterable[str]) -> list[float]:
    defs = [get_metric_definition(name) for name in names]
    total = sum(defn.weight for defn in defs)
    return [defn.weight / total for defn in defs]


def build_multi_metric_objective(
    names: Iterable[str],
    evaluation_fn: Callable[[dict[str, Any], str], dict[str, Any]],
    handle_exception: Callable[[str, Exception], score_result.ScoreResult],
    objective_name: str = "arc_agi2_multi",
) -> MultiMetricObjective:
    """
    Build the canonical ARC-AGI MultiMetric objective.

    Parameters
    ----------
    names:
        Metric identifiers to include.  The default call site uses
        ``DEFAULT_METRIC_SEQUENCE`` and mirrors the weights documented in
        :data:`METRIC_DEFINITIONS`.
    evaluation_fn:
        Callable that executes the Python candidate, returning the evaluation
        payload consumed by each metric extractor.  In practice this is
        :func:`evaluate_arc_response`.
    handle_exception:
        Callback invoked when scoring fails.  This lets the orchestrator decide
        whether to surface errors or treat them as a zero reward.
    objective_name:
        Name surfaced to Opik dashboards.

    Returns
    -------
    MultiMetricObjective
        Ready-to-use objective that automatically applies the correct weights
        and reasoning metadata.  Downstream callers simply pass it into HRPO.
        The weights correspond to :data:`METRIC_DEFINITIONS` so updating this
        registry keeps both MultiMetric and EvaluationConfig in sync.
    """

    metric_functions = build_metric_functions(names, evaluation_fn, handle_exception)
    weights = normalized_weights(names)
    return MultiMetricObjective(
        metrics=metric_functions,
        weights=weights,
        name=objective_name,
    )


__all__ = [
    "MetricDefinition",
    "METRIC_DEFINITIONS",
    "build_metric_function",
    "build_metric_functions",
    "normalized_weights",
    "build_multi_metric_objective",
    "DEFAULT_METRIC_SEQUENCE",
    "DEFAULT_PASS_AT_K",
    "LIKENESS_REWARD_WEIGHT",
    "LABEL_IOU_REWARD_WEIGHT",
    "FOREGROUND_REWARD_WEIGHT",
    "approx_match_score",
    "label_iou",
    "foreground_match_score",
    "get_metric_definition",
]
