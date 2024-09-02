import re
from typing import Any, Union

from .. import base_metric, score_result


class RegexMatch(base_metric.BaseMetric):
    """
    A metric that checks if an output string matches a given regular expression pattern.

    This metric returns a score of 1.0 if the output string matches the regex pattern,
    and 0.0 otherwise.

    Args:
        regex: The regular expression pattern to match against. Can be a string or a compiled regex pattern.
        name: The name of the metric. Defaults to "regex_match_metric".

    Example:
        >>> from comet_llm_eval.evaluation.metrics import RegexMatch
        >>> regex_metric = RegexMatch(r"\d{3}-\d{2}-\d{4}")
        >>> result = regex_metric.score("My SSN is 123-45-6789")
        >>> print(result.value)
        1.0
        >>> result = regex_metric.score("My phone is 555-1234")
        >>> print(result.value)
        0.0
    """

    def __init__(
        self,
        regex: Union[str, re.Pattern],
        name: str = "regex_match_metric",
    ):
        super().__init__(name=name)

        self._regex_pattern: re.Pattern = (
            re.compile(regex) if isinstance(regex, str) else regex
        )

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the output string matches the regex pattern.

        Args:
            output: The output string to check against the regex pattern.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the output
                matches the regex pattern, 0.0 otherwise.
        """
        if self._regex_pattern.search(output):
            return score_result.ScoreResult(value=1.0, name=self.name)

        return score_result.ScoreResult(value=0.0, name=self.name)
