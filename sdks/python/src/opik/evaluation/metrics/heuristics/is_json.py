import json
from typing import Any

from .. import base_metric, score_result


class IsJson(base_metric.BaseMetric):
    """
    A metric that checks if a given output string is valid JSON.

    This metric returns a score of 1.0 if the output string can be parsed as JSON,
    and 0.0 otherwise.

    Args:
        name: The name of the metric. Defaults to "is_json_metric".

    Example:
        >>> from comet_llm_eval.evaluation.metrics import IsJson
        >>> is_json_metric = IsJson()
        >>> result = is_json_metric.score('{"key": "value"}')
        >>> print(result.value)
        1.0
        >>> result = is_json_metric.score('Not a JSON string')
        >>> print(result.value)
        0.0
    """

    def __init__(self, name: str = "is_json_metric") -> None:
        super().__init__(name)

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the output string is valid JSON.

        Args:
            output: The output string to check for JSON validity.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the output
                is valid JSON, 0.0 otherwise.
        """
        try:
            json.loads(output)
            return score_result.ScoreResult(value=1.0, name=self.name)
        except Exception:
            return score_result.ScoreResult(value=0.0, name=self.name)
