from typing import Any

from .. import base_metric, score_result


class Equals(base_metric.BaseMetric):
    """
    A metric that checks if an output string exactly matches an expected output string.

    This metric returns a score of 1.0 if the strings match exactly, and 0.0 otherwise.
    The comparison can be made case-sensitive or case-insensitive.

    Args:
        case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
        name: The name of the metric. Defaults to "equals_metric".

    Example:
        >>> from comet_llm_eval.evaluation.metrics import Equals
        >>> equals_metric = Equals(case_sensitive=True)
        >>> result = equals_metric.score("Hello, World!", "Hello, World!")
        >>> print(result.value)
        1.0
        >>> result = equals_metric.score("Hello, World!", "hello, world!")
        >>> print(result.value)
        0.0
    """

    def __init__(
        self,
        case_sensitive: bool = False,
        name: str = "equals_metric",
    ):
        super().__init__(name=name)
        self._case_sensitive = case_sensitive

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the output string exactly matches the expected output.

        Args:
            output: The output string to check.
            reference: The expected output string to compare against.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the strings match,
                0.0 otherwise.
        """
        value_left = output if self._case_sensitive else output.lower()
        value_right = reference if self._case_sensitive else reference.lower()

        if value_left == value_right:
            return score_result.ScoreResult(value=1.0, name=self.name)

        return score_result.ScoreResult(value=0.0, name=self.name)
