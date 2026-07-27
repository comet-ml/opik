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


def _is_hashable(value: Any) -> bool:
    try:
        hash(value)
    except TypeError:
        return False
    return True


def _validate(
    output_key: str,
    reference_key: str,
    average: str,
    positive_label: Optional[Any],
    name: Optional[str],
) -> None:
    if not isinstance(output_key, str) or not output_key.strip():
        raise ValueError("output_key must be a non-empty string")
    if not isinstance(reference_key, str) or not reference_key.strip():
        raise ValueError("reference_key must be a non-empty string")
    if name is not None and (not isinstance(name, str) or not name.strip()):
        raise ValueError("name must be a non-empty string")
    if average not in _AVERAGES:
        raise ValueError(f"average must be one of {_AVERAGES}, got {average!r}")
    if average == "binary" and positive_label is None:
        raise ValueError("positive_label is required when average is 'binary'")
    if average != "binary" and positive_label is not None:
        raise ValueError(
            f"positive_label only applies when average is 'binary', "
            f"got average={average!r}"
        )
    if positive_label is not None and not _is_hashable(positive_label):
        raise ValueError(
            f"positive_label must be hashable to name a class, got "
            f"{type(positive_label).__name__}"
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
    # Single pass: a per-label rescan would be O(rows x labels), and the label
    # count is unbounded when a model emits malformed predictions.
    tallies: Dict[Any, List[int]] = {}
    for truth, pred in pairs:
        for label in (truth, pred):
            if label not in tallies:
                tallies[label] = [0, 0, 0]
        if truth == pred:
            tallies[truth][0] += 1
        else:
            tallies[pred][1] += 1
            tallies[truth][2] += 1
    return {label: (tp, fp, fn) for label, (tp, fp, fn) in tallies.items()}


def _first_unusable(pairs: List[Tuple[Any, Any]]) -> Optional[Tuple[Any, str]]:
    # A list or dict here means the task emitted a structured blob instead of a
    # class. Left alone it raises TypeError from the tally lookup, which
    # compute_experiment_scores swallows - the metric would vanish silently.
    # NaN is hashable but never equals itself, so it slips past that check and
    # instead tallies one class per occurrence, quietly sinking the average.
    for pair in pairs:
        for label in pair:
            if not _is_hashable(label):
                return label, "is not hashable"
            if label != label:
                return label, "does not equal itself"
    return None


def _label_sort_key(label: Any) -> Tuple[str, str]:
    # repr alone ties for values that stringify alike (1 vs "1"), which would
    # leave the order at the mercy of dict insertion.
    return type(label).__name__, repr(label)


def _reduce(
    counts: Counts,
    average: str,
    metric_fn: MetricFn,
    positive_label: Optional[Any],
) -> float:
    if average == "binary":
        return metric_fn(*counts[positive_label])

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
    _validate(output_key, reference_key, average, positive_label, name)
    if name:
        score_name = name
    elif average == "binary":
        # The label keeps two binary scorers on one metric apart. Labels that
        # stringify alike (1 vs "1") still collide - `name` is the escape hatch,
        # as it is for any two scorers that would share a default name.
        score_name = f"{metric_name}_{average}_{positive_label}"
    else:
        score_name = f"{metric_name}_{average}"

    def score(
        test_results: List["test_result.TestResult"],
    ) -> score_result.ScoreResult:
        pairs, skipped = _extract_pairs(test_results, output_key, reference_key)
        metadata: Dict[str, Any] = {
            "average": average,
            "n_samples": len(pairs),
            "n_skipped": skipped,
        }

        unusable = _first_unusable(pairs)
        if unusable is not None:
            label, cause = unusable
            return score_result.ScoreResult(
                name=score_name,
                value=0.0,
                reason=(
                    f"Label {label!r} {cause}, so it cannot name a class: "
                    f"'{output_key}' and '{reference_key}' must hold scalar "
                    f"labels."
                ),
                metadata=metadata,
                scoring_failed=True,
            )

        counts = _class_counts(pairs)
        labels = sorted(counts, key=_label_sort_key)
        metadata["labels"] = labels

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

        if average == "binary" and positive_label not in counts:
            return score_result.ScoreResult(
                name=score_name,
                value=0.0,
                reason=(
                    f"positive_label {positive_label!r} never appears among the "
                    f"reference or predicted labels {labels}, so the score is "
                    f"undefined rather than zero."
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
    """Experiment-level precision, for ``evaluate(experiment_scoring_functions=...)``.

    ``average`` is one of ``binary``, ``macro``, ``micro`` or ``weighted``, and
    ``binary`` requires ``positive_label``. Rows whose task output or dataset item
    lacks the requested key are skipped and counted in the result's ``reason``.
    """
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
    """Experiment-level recall, for ``evaluate(experiment_scoring_functions=...)``.

    ``average`` is one of ``binary``, ``macro``, ``micro`` or ``weighted``, and
    ``binary`` requires ``positive_label``. Rows whose task output or dataset item
    lacks the requested key are skipped and counted in the result's ``reason``.
    """
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
    """Experiment-level F1, for ``evaluate(experiment_scoring_functions=...)``.

    ``average`` is one of ``binary``, ``macro``, ``micro`` or ``weighted``, and
    ``binary`` requires ``positive_label``. Rows whose task output or dataset item
    lacks the requested key are skipped and counted in the result's ``reason``.
    """
    return _build(
        "f1_score", _f1, output_key, reference_key, average, positive_label, name
    )
