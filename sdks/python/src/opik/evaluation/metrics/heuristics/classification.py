from typing import Any, List, Literal, Optional


from sklearn.metrics import f1_score, precision_score, recall_score


from .. import base_metric, score_result


class F1Score(base_metric.BaseMetric):
    """
    A metric that computes the F1-score for classification tasks at the dataset level.


    Unlike per-sample metrics, this metric requires all predictions and references
    to be collected first, then scored together.


    Args:
        average: Averaging strategy - 'macro', 'micro', or 'weighted'. Defaults to 'macro'.
        name: The name of the metric. Defaults to "f1_score_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name.


    Example:
        >>> from opik.evaluation.metrics.heuristics.classification import F1Score
        >>> metric = F1Score(average="macro")
        >>> result = metric.score(
        ...     predictions=["cat", "dog", "cat"],
        ...     references=["cat", "cat", "cat"]
        ... )
        >>> print(result.value)
    """

    def __init__(
        self,
        average: Literal["macro", "micro", "weighted"] = "macro",
        name: str = "f1_score_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._average = average

    def score(
        self,
        predictions: List[Any],
        references: List[Any],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Compute F1-score across all predictions and references.


        Args:
            predictions: List of predicted labels.
            references: List of ground truth labels.
            **ignored_kwargs: Additional keyword arguments that are ignored.


        Returns:
            ScoreResult with F1-score value and reasoning.
        """
        if len(predictions) != len(references):
            raise ValueError(
                f"predictions and references must have the same length, "
                f"got {len(predictions)} and {len(references)}"
            )

        value = f1_score(
            references, predictions, average=self._average, zero_division=0
        )

        return score_result.ScoreResult(
            value=float(value),
            name=self.name,
            reason=f"F1-score ({self._average}) computed over {len(predictions)} samples",
        )


class PrecisionScore(base_metric.BaseMetric):
    """
    A metric that computes Precision for classification tasks at the dataset level.


    Args:
        average: Averaging strategy - 'macro', 'micro', or 'weighted'. Defaults to 'macro'.
        name: The name of the metric. Defaults to "precision_score_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name.


    Example:
        >>> from opik.evaluation.metrics.heuristics.classification import PrecisionScore
        >>> metric = PrecisionScore(average="weighted")
        >>> result = metric.score(
        ...     predictions=["cat", "dog", "cat"],
        ...     references=["cat", "cat", "cat"]
        ... )
        >>> print(result.value)
    """

    def __init__(
        self,
        average: Literal["macro", "micro", "weighted"] = "macro",
        name: str = "precision_score_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._average = average

    def score(
        self,
        predictions: List[Any],
        references: List[Any],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Compute Precision across all predictions and references.


        Args:
            predictions: List of predicted labels.
            references: List of ground truth labels.
            **ignored_kwargs: Additional keyword arguments that are ignored.


        Returns:
            ScoreResult with Precision value and reasoning.
        """
        if len(predictions) != len(references):
            raise ValueError(
                f"predictions and references must have the same length, "
                f"got {len(predictions)} and {len(references)}"
            )

        value = precision_score(
            references, predictions, average=self._average, zero_division=0
        )

        return score_result.ScoreResult(
            value=float(value),
            name=self.name,
            reason=f"Precision ({self._average}) computed over {len(predictions)} samples",
        )


class RecallScore(base_metric.BaseMetric):
    """
    A metric that computes Recall for classification tasks at the dataset level.


    Args:
        average: Averaging strategy - 'macro', 'micro', or 'weighted'. Defaults to 'macro'.
        name: The name of the metric. Defaults to "recall_score_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name.


    Example:
        >>> from opik.evaluation.metrics.heuristics.classification import RecallScore
        >>> metric = RecallScore(average="micro")
        >>> result = metric.score(
        ...     predictions=["cat", "dog", "cat"],
        ...     references=["cat", "cat", "cat"]
        ... )
        >>> print(result.value)
    """

    def __init__(
        self,
        average: Literal["macro", "micro", "weighted"] = "macro",
        name: str = "recall_score_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._average = average

    def score(
        self,
        predictions: List[Any],
        references: List[Any],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Compute Recall across all predictions and references.


        Args:
            predictions: List of predicted labels.
            references: List of ground truth labels.
            **ignored_kwargs: Additional keyword arguments that are ignored.


        Returns:
            ScoreResult with Recall value and reasoning.
        """
        if len(predictions) != len(references):
            raise ValueError(
                f"predictions and references must have the same length, "
                f"got {len(predictions)} and {len(references)}"
            )

        value = recall_score(
            references, predictions, average=self._average, zero_division=0
        )

        return score_result.ScoreResult(
            value=float(value),
            name=self.name,
            reason=f"Recall ({self._average}) computed over {len(predictions)} samples",
        )
