import dataclasses


@dataclasses.dataclass
class ExperimentMetricResult:
    """
    Represents the result of an experiment-level metric computation.

    Experiment metrics are computed on the entire evaluation result (across all test cases)
    and provide aggregate statistics for specific scoring metrics.

    Args:
        score_name: The name of the scoring metric this aggregate is for (e.g., "hallucination", "accuracy")
        metric_name: The name of the aggregate metric (e.g., "avg", "median", "p95")
        value: The computed metric value

    Example:
        >>> result = ExperimentMetricResult(
        ...     score_name="hallucination",
        ...     metric_name="avg",
        ...     value=0.85
        ... )
    """

    score_name: str
    metric_name: str
    value: float
