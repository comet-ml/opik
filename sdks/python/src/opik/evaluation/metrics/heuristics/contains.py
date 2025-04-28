from typing import Any, Optional

from .. import base_metric, score_result


class Contains(base_metric.BaseMetric):
    """
    A metric that checks if a reference string is contained within an output string.

    This metric returns a score of 1.0 if the reference string is found within the output string,
    and 0.0 otherwise. The comparison can be made case-sensitive or case-insensitive.

    Args:
        case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
        name: The name of the metric. Defaults to "contains_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import Contains
        >>> contains_metric = Contains(case_sensitive=True)
        >>> result = contains_metric.score("Hello, World!", "World")
        >>> print(result.value)
        1.0
        >>> result = contains_metric.score("Hello, World!", "world")
        >>> print(result.value)
        0.0
    """

    def __init__(
        self,
        case_sensitive: bool = False,
        name: str = "contains_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )

        self._case_sensitive = case_sensitive

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the reference string is contained in the output string.

        Args:
            output: The output string to check.
            reference: The reference string to look for in the output.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the reference
                is found in the output, 0.0 otherwise.
        """
        value = output if self._case_sensitive else output.lower()
        reference = reference if self._case_sensitive else reference.lower()

        if reference in value:
            return score_result.ScoreResult(value=1.0, name=self.name)

        return score_result.ScoreResult(value=0.0, name=self.name)
