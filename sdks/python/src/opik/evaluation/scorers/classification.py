"""Experiment-level classification scorers: precision, recall and F1.

These are deliberately not ``BaseMetric`` subclasses. Precision/recall/F1 are
undefined for a single sample - they only exist over a whole dataset - so they
plug into ``evaluate(experiment_scoring_functions=...)`` instead.
"""

from typing import Any, Callable, Dict, List, Optional, Tuple, TYPE_CHECKING

from opik.evaluation.metrics import score_result

if TYPE_CHECKING:
    from opik.evaluation import test_result, types


_MISSING = object()
_AVERAGES = ("binary", "macro", "micro", "weighted")

Counts = Dict[Any, Tuple[int, int, int]]
MetricFn = Callable[[int, int, int], float]


def _validate(
    output_key: str,
    reference_key: str,
    average: str,
    positive_label: Optional[Any],
) -> None:
    if not isinstance(output_key, str) or not output_key.strip():
        raise ValueError("output_key must be a non-empty string")
    if not isinstance(reference_key, str) or not reference_key.strip():
        raise ValueError("reference_key must be a non-empty string")
    if average not in _AVERAGES:
        raise ValueError(f"average must be one of {_AVERAGES}, got {average!r}")
    if average == "binary" and positive_label is None:
        raise ValueError("positive_label is required when average is 'binary'")
    if average != "binary" and positive_label is not None:
        raise ValueError(
            f"positive_label only applies when average is 'binary', "
            f"got average={average!r}"
        )


def _precision(tp: int, fp: int, fn: int) -> float:
    return tp / (tp + fp) if tp + fp else 0.0


def _recall(tp: int, fp: int, fn: int) -> float:
    return tp / (tp + fn) if tp + fn else 0.0


def _f1(tp: int, fp: int, fn: int) -> float:
    precision_value = _precision(tp, fp, fn)
    recall_value = _recall(tp, fp, fn)
    if precision_value + recall_value == 0:
        return 0.0
    return 2 * precision_value * recall_value / (precision_value + recall_value)


def _extract_pairs(
    test_results: List["test_result.TestResult"],
    output_key: str,
    reference_key: str,
) -> Tuple[List[Tuple[Any, Any]], int]:
    pairs = []
    skipped = 0
    for result in test_results:
        predicted = result.test_case.task_output.get(output_key, _MISSING)
        reference = result.test_case.dataset_item_content.get(reference_key, _MISSING)
        if predicted is _MISSING or reference is _MISSING:
            skipped += 1
            continue
        pairs.append((reference, predicted))
    return pairs, skipped


def _class_counts(pairs: List[Tuple[Any, Any]]) -> Counts:
    labels = {label for pair in pairs for label in pair}
    counts: Counts = {}
    for label in labels:
        tp = sum(1 for truth, pred in pairs if truth == label and pred == label)
        fp = sum(1 for truth, pred in pairs if pred == label and truth != label)
        fn = sum(1 for truth, pred in pairs if truth == label and pred != label)
        counts[label] = (tp, fp, fn)
    return counts


def _reduce(
    counts: Counts,
    average: str,
    metric_fn: MetricFn,
    positive_label: Optional[Any],
) -> float:
    if average == "binary":
        return metric_fn(*counts.get(positive_label, (0, 0, 0)))

    if average == "micro":
        pooled = tuple(sum(column) for column in zip(*counts.values()))
        return metric_fn(*pooled)

    if average == "macro":
        return sum(metric_fn(*count) for count in counts.values()) / len(counts)

    total_support = sum(tp + fn for tp, _, fn in counts.values())
    if total_support == 0:
        return 0.0
    return (
        sum((tp + fn) * metric_fn(tp, fp, fn) for tp, fp, fn in counts.values())
        / total_support
    )


def _build(
    metric_name: str,
    metric_fn: MetricFn,
    output_key: str,
    reference_key: str,
    average: str,
    positive_label: Optional[Any],
    name: Optional[str],
) -> "types.ExperimentScoreFunction":
    _validate(output_key, reference_key, average, positive_label)
    score_name = name or f"{metric_name}_{average}"

    def score(
        test_results: List["test_result.TestResult"],
    ) -> score_result.ScoreResult:
        pairs, skipped = _extract_pairs(test_results, output_key, reference_key)
        counts = _class_counts(pairs)
        metadata = {
            "average": average,
            "n_samples": len(pairs),
            "n_skipped": skipped,
            # Labels may be a mix of types, so sort by their string form.
            "labels": sorted(counts, key=str),
        }

        if not pairs:
            return score_result.ScoreResult(
                name=score_name,
                value=0.0,
                reason=(
                    f"No rows to score: all {skipped} were missing "
                    f"'{output_key}' or '{reference_key}'."
                ),
                metadata=metadata,
                scoring_failed=True,
            )

        reason = None
        if skipped:
            reason = (
                f"Scored {len(pairs)} rows, skipped {skipped} missing "
                f"'{output_key}' or '{reference_key}'."
            )

        return score_result.ScoreResult(
            name=score_name,
            value=_reduce(counts, average, metric_fn, positive_label),
            reason=reason,
            metadata=metadata,
        )

    return score


def precision(
    *,
    output_key: str,
    reference_key: str,
    average: str = "macro",
    positive_label: Optional[Any] = None,
    name: Optional[str] = None,
) -> "types.ExperimentScoreFunction":
    return _build(
        "precision",
        _precision,
        output_key,
        reference_key,
        average,
        positive_label,
        name,
    )


def recall(
    *,
    output_key: str,
    reference_key: str,
    average: str = "macro",
    positive_label: Optional[Any] = None,
    name: Optional[str] = None,
) -> "types.ExperimentScoreFunction":
    return _build(
        "recall", _recall, output_key, reference_key, average, positive_label, name
    )


def f1_score(
    *,
    output_key: str,
    reference_key: str,
    average: str = "macro",
    positive_label: Optional[Any] = None,
    name: Optional[str] = None,
) -> "types.ExperimentScoreFunction":
    return _build(
        "f1_score", _f1, output_key, reference_key, average, positive_label, name
    )
