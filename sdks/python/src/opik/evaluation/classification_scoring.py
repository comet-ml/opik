"""
Experiment-level classification scoring functions for Opik.


These functions are designed to be used with the
``experiment_scoring_functions`` parameter of :func:`opik.evaluate`.
Unlike per-sample metrics, they operate over the full set of
test results to compute dataset-level classification metrics.


Example::


    from opik.evaluation.classification_scoring import (
        f1_scoring_function,
        precision_scoring_function,
        recall_scoring_function,
    )


    opik.evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[Equals()],
        experiment_scoring_functions=[
            f1_scoring_function(),
            precision_scoring_function(),
            recall_scoring_function(),
        ],
    )
"""

from typing import Any, Callable, List, Literal


import sklearn.metrics


from opik.evaluation import test_result
from opik.evaluation.metrics import score_result


def f1_scoring_function(
    output_key: str = "output",
    reference_key: str = "reference",
    average: Literal["macro", "micro", "weighted"] = "macro",
    name: str = "f1_score",
) -> Callable[[List[test_result.TestResult]], List[score_result.ScoreResult]]:
    """
    Return an experiment-level scoring function that computes F1-score.


    The returned callable is passed directly to ``experiment_scoring_functions``
    in :func:`opik.evaluate`.


    Args:
        output_key: Key in ``task_output`` that holds the predicted label.
        reference_key: Key in ``task_output`` that holds the ground-truth label.
        average: Averaging strategy — ``"macro"``, ``"micro"``, or
            ``"weighted"``. Defaults to ``"macro"``.
        name: Name of the returned :class:`~opik.evaluation.metrics.score_result.ScoreResult`.


    Returns:
        A callable ``(List[TestResult]) -> List[ScoreResult]``.


    Example::


        experiment_scoring_functions=[f1_scoring_function(average="weighted")]
    """

    def _compute(
        test_results: List[test_result.TestResult],
    ) -> List[score_result.ScoreResult]:
        predictions = [
            str(r.test_case.task_output.get(output_key, ""))
            for r in test_results
            if r.test_case.task_output is not None
        ]
        references = [
            str(r.test_case.task_output.get(reference_key, ""))
            for r in test_results
            if r.test_case.task_output is not None
        ]

        if not predictions:
            return [
                score_result.ScoreResult(
                    name=name,
                    value=0.0,
                    reason="No valid predictions found in test results.",
                )
            ]

        value = sklearn.metrics.f1_score(
            references,
            predictions,
            average=average,
            zero_division=0,
        )
        return [
            score_result.ScoreResult(
                name=name,
                value=float(value),
                reason=(
                    f"F1-score ({average}) computed over "
                    f"{len(predictions)} samples."
                ),
            )
        ]

    _compute.__name__ = name
    return _compute


def precision_scoring_function(
    output_key: str = "output",
    reference_key: str = "reference",
    average: Literal["macro", "micro", "weighted"] = "macro",
    name: str = "precision_score",
) -> Callable[[List[test_result.TestResult]], List[score_result.ScoreResult]]:
    """
    Return an experiment-level scoring function that computes Precision.


    Args:
        output_key: Key in ``task_output`` that holds the predicted label.
        reference_key: Key in ``task_output`` that holds the ground-truth label.
        average: Averaging strategy — ``"macro"``, ``"micro"``, or
            ``"weighted"``. Defaults to ``"macro"``.
        name: Name of the returned :class:`~opik.evaluation.metrics.score_result.ScoreResult`.


    Returns:
        A callable ``(List[TestResult]) -> List[ScoreResult]``.


    Example::


        experiment_scoring_functions=[precision_scoring_function(average="micro")]
    """

    def _compute(
        test_results: List[test_result.TestResult],
    ) -> List[score_result.ScoreResult]:
        predictions = [
            str(r.test_case.task_output.get(output_key, ""))
            for r in test_results
            if r.test_case.task_output is not None
        ]
        references = [
            str(r.test_case.task_output.get(reference_key, ""))
            for r in test_results
            if r.test_case.task_output is not None
        ]

        if not predictions:
            return [
                score_result.ScoreResult(
                    name=name,
                    value=0.0,
                    reason="No valid predictions found in test results.",
                )
            ]

        value = sklearn.metrics.precision_score(
            references,
            predictions,
            average=average,
            zero_division=0,
        )
        return [
            score_result.ScoreResult(
                name=name,
                value=float(value),
                reason=(
                    f"Precision ({average}) computed over "
                    f"{len(predictions)} samples."
                ),
            )
        ]

    _compute.__name__ = name
    return _compute


def recall_scoring_function(
    output_key: str = "output",
    reference_key: str = "reference",
    average: Literal["macro", "micro", "weighted"] = "macro",
    name: str = "recall_score",
) -> Callable[[List[test_result.TestResult]], List[score_result.ScoreResult]]:
    """
    Return an experiment-level scoring function that computes Recall.


    Args:
        output_key: Key in ``task_output`` that holds the predicted label.
        reference_key: Key in ``task_output`` that holds the ground-truth label.
        average: Averaging strategy — ``"macro"``, ``"micro"``, or
            ``"weighted"``. Defaults to ``"macro"``.
        name: Name of the returned :class:`~opik.evaluation.metrics.score_result.ScoreResult`.


    Returns:
        A callable ``(List[TestResult]) -> List[ScoreResult]``.


    Example::


        experiment_scoring_functions=[recall_scoring_function(average="weighted")]
    """

    def _compute(
        test_results: List[test_result.TestResult],
    ) -> List[score_result.ScoreResult]:
        predictions = [
            str(r.test_case.task_output.get(output_key, ""))
            for r in test_results
            if r.test_case.task_output is not None
        ]
        references = [
            str(r.test_case.task_output.get(reference_key, ""))
            for r in test_results
            if r.test_case.task_output is not None
        ]

        if not predictions:
            return [
                score_result.ScoreResult(
                    name=name,
                    value=0.0,
                    reason="No valid predictions found in test results.",
                )
            ]

        value = sklearn.metrics.recall_score(
            references,
            predictions,
            average=average,
            zero_division=0,
        )
        return [
            score_result.ScoreResult(
                name=name,
                value=float(value),
                reason=(
                    f"Recall ({average}) computed over " f"{len(predictions)} samples."
                ),
            )
        ]

    _compute.__name__ = name
    return _compute
