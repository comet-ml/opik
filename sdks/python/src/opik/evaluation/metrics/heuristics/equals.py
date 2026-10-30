from typing import Any, Callable, Optional, Set

from .. import base_metric, score_result
from opik.exceptions import MetricComputationError

_CONTAINER_TYPES = (dict, list, tuple, set, frozenset)


def _is_container(value: Any) -> bool:
    # A namedtuple prints with its field names, so it keeps being compared by str().
    return isinstance(value, _CONTAINER_TYPES) and not hasattr(value, "_fields")


def _canonical_text(
    value: Any, normalize: Callable[[str], str], active_ids: Set[int]
) -> str:
    # Recursion guard: a container that holds itself renders as "...", as in str().
    if not _is_container(value):
        return normalize(repr(value))
    if id(value) in active_ids:
        return "..."
    active_ids.add(id(value))
    # Read the built-in type's contents, as str() does, not a subclass override.
    base = next(kind for kind in _CONTAINER_TYPES if isinstance(value, kind))
    try:
        if base is dict:
            # The original key breaks ties, so keys that differ only in case
            # keep their own values when the comparison ignores case.
            entries = sorted(
                (
                    _canonical_text(key, normalize, active_ids),
                    _canonical_text(key, _keep_case, active_ids),
                    _canonical_text(item, normalize, active_ids),
                )
                for key, item in dict.items(value)
            )
            return "{" + ", ".join(f"{key}: {item}" for key, _, item in entries) + "}"
        rendered = [
            _canonical_text(item, normalize, active_ids)
            for item in base.__iter__(value)
        ]
        if base is list:
            return "[" + ", ".join(rendered) + "]"
        if base is tuple:
            return "(" + ", ".join(rendered) + ("," if len(rendered) == 1 else "") + ")"
        return base.__name__ + "{" + ", ".join(sorted(rendered)) + "}"
    finally:
        active_ids.discard(id(value))


def _keep_case(text: str) -> str:
    return text


class Equals(base_metric.BaseMetric):
    """
    A metric that checks if an output string exactly matches an expected output string.

    This metric returns a score of 1.0 if the strings match exactly, and 0.0 otherwise.
    The comparison can be made case-sensitive or case-insensitive.

    When both values are dicts, lists, tuples or sets, they are compared by content:
    dict key order and set order do not matter, list and tuple order does, and
    strings inside them follow ``case_sensitive``. Other values are compared by
    their ``str()`` form.

    Args:
        case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
        name: The name of the metric. Defaults to "equals_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import Equals
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
        self, output: Any, reference: Any, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the output exactly matches the expected output.

        Args:
            output: The output to check. Will be converted to string for comparison.
            reference: The expected output to compare against. Will be converted to string for comparison.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the values match,
                0.0 otherwise.
        """
        if output is None or reference is None:
            raise MetricComputationError(
                f"Equals metric requires non-None 'output' and 'reference' arguments, "
                f"got output={output!r}, reference={reference!r}"
            )

        if _is_container(output) and _is_container(reference):
            normalize = _keep_case if self._case_sensitive else str.lower
            value_left = _canonical_text(output, normalize, set())
            value_right = _canonical_text(reference, normalize, set())
        else:
            # Convert to string to handle numeric and other types
            output_str = str(output)
            reference_str = str(reference)

            value_left = output_str if self._case_sensitive else output_str.lower()
            value_right = (
                reference_str if self._case_sensitive else reference_str.lower()
            )

        if value_left == value_right:
            return score_result.ScoreResult(value=1.0, name=self.name)

        return score_result.ScoreResult(value=0.0, name=self.name)
