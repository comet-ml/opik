from typing import Any, List, Optional, Sequence, Tuple, Union

from .. import test_case, test_result
from ..metrics import score_result

try:
    from sklearn import metrics as sklearn_metrics
except ImportError:  # pragma: no cover
    sklearn_metrics = None

_ALLOWED_AVERAGES = {"binary", "micro", "macro", "weighted"}
_ZeroDivisionType = Union[int, float, str]


def _require_sklearn() -> Any:
    if sklearn_metrics is None:  # pragma: no cover
        raise ImportError(
            "scikit-learn is required for classification scoring functions. "
            "Install it with 'pip install scikit-learn'."
        )
    return sklearn_metrics


def _resolve_label_value(
    case: test_case.TestCase,
    key: str,
) -> Tuple[bool, Any]:
    mapped_inputs = case.mapped_scoring_inputs
    if mapped_inputs is not None and key in mapped_inputs:
        value = mapped_inputs[key]
        return (value is not None), value

    if key in case.task_output:
        value = case.task_output[key]
        return (value is not None), value

    if key in case.dataset_item_content:
        value = case.dataset_item_content[key]
        return (value is not None), value

    return False, None


def _collect_labels(
    test_results: List[test_result.TestResult],
    prediction_key: str,
    reference_key: str,
) -> Tuple[List[Any], List[Any], int]:
    y_true: List[Any] = []
    y_pred: List[Any] = []
    skipped = 0

    for result in test_results:
        case = result.test_case
        has_pred, pred = _resolve_label_value(case, prediction_key)
        has_ref, ref = _resolve_label_value(case, reference_key)

        if not has_pred or not has_ref:
            skipped += 1
            continue

        y_true.append(ref)
        y_pred.append(pred)

    return y_true, y_pred, skipped


def _validate_average(average: str) -> None:
    if average not in _ALLOWED_AVERAGES:
        raise ValueError(
            f"Unsupported average='{average}'. Expected one of: {sorted(_ALLOWED_AVERAGES)}."
        )


def _build_reason(metric_name: str, average: str, total: int, skipped: int) -> str:
    reason = f"{metric_name} ({average}) over {total} samples"
    if skipped:
        reason += f"; skipped {skipped} missing labels"
    return reason


def _classification_score(
    *,
    metric_name: str,
    metric_fn: Any,
    test_results: List[test_result.TestResult],
    average: str,
    prediction_key: str,
    reference_key: str,
    labels: Optional[Sequence[Any]],
    pos_label: Any,
    zero_division: _ZeroDivisionType,
    name: Optional[str],
) -> List[score_result.ScoreResult]:
    _validate_average(average)

    y_true, y_pred, skipped = _collect_labels(
        test_results=test_results,
        prediction_key=prediction_key,
        reference_key=reference_key,
    )

    if not y_true:
        return []

    value = metric_fn(
        y_true,
        y_pred,
        average=average,
        labels=labels,
        pos_label=pos_label,
        zero_division=zero_division,
    )

    result_name = name or f"{metric_name}_{average}"
    return [
        score_result.ScoreResult(
            name=result_name,
            value=float(value),
            reason=_build_reason(metric_name, average, len(y_true), skipped),
            metadata={
                "average": average,
                "samples": len(y_true),
                "skipped": skipped,
                "prediction_key": prediction_key,
                "reference_key": reference_key,
            },
        )
    ]


def classification_f1_score(
    test_results: List[test_result.TestResult],
    *,
    average: str = "macro",
    prediction_key: str = "output",
    reference_key: str = "reference",
    labels: Optional[Sequence[Any]] = None,
    pos_label: Any = 1,
    zero_division: _ZeroDivisionType = 0.0,
    name: Optional[str] = None,
) -> List[score_result.ScoreResult]:
    """Compute dataset-level F1 for classification tasks."""
    return _classification_score(
        metric_name="classification_f1",
        metric_fn=_require_sklearn().f1_score,
        test_results=test_results,
        average=average,
        prediction_key=prediction_key,
        reference_key=reference_key,
        labels=labels,
        pos_label=pos_label,
        zero_division=zero_division,
        name=name,
    )


def classification_precision_score(
    test_results: List[test_result.TestResult],
    *,
    average: str = "macro",
    prediction_key: str = "output",
    reference_key: str = "reference",
    labels: Optional[Sequence[Any]] = None,
    pos_label: Any = 1,
    zero_division: _ZeroDivisionType = 0.0,
    name: Optional[str] = None,
) -> List[score_result.ScoreResult]:
    """Compute dataset-level precision for classification tasks."""
    return _classification_score(
        metric_name="classification_precision",
        metric_fn=_require_sklearn().precision_score,
        test_results=test_results,
        average=average,
        prediction_key=prediction_key,
        reference_key=reference_key,
        labels=labels,
        pos_label=pos_label,
        zero_division=zero_division,
        name=name,
    )


def classification_recall_score(
    test_results: List[test_result.TestResult],
    *,
    average: str = "macro",
    prediction_key: str = "output",
    reference_key: str = "reference",
    labels: Optional[Sequence[Any]] = None,
    pos_label: Any = 1,
    zero_division: _ZeroDivisionType = 0.0,
    name: Optional[str] = None,
) -> List[score_result.ScoreResult]:
    """Compute dataset-level recall for classification tasks."""
    return _classification_score(
        metric_name="classification_recall",
        metric_fn=_require_sklearn().recall_score,
        test_results=test_results,
        average=average,
        prediction_key=prediction_key,
        reference_key=reference_key,
        labels=labels,
        pos_label=pos_label,
        zero_division=zero_division,
        name=name,
    )
