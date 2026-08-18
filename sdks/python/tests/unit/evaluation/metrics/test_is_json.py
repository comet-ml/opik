"""Tests for the `IsJson` heuristic metric.

The metric is publicly exported as `opik.evaluation.metrics.IsJson` and is
commonly used to validate LLM output in structured extraction / tool-calling
eval pipelines. The behaviour covered here:

- Valid JSON (objects, arrays, primitives) scores 1.0.
- Invalid JSON (malformed, empty, whitespace) scores 0.0.
- Non-string inputs (None, int, list, dict, bytes) are silently caught by the
  metric's `except Exception` and also score 0.0; the test pins this so the
  failure mode cannot drift unnoticed.
- The metric's default and custom `name` propagate to the `ScoreResult`.
- Extra keyword arguments passed to `score()` are ignored (the metric
  signature is `**ignored_kwargs`).

Follows the existing test pattern from `test_heuristics.py` and
`test_sentiment.py`: instantiate with `track=False`, assert against the
full `ScoreResult` (name + value).
"""

import pytest

from opik.evaluation.metrics import IsJson
from opik.evaluation.metrics.score_result import ScoreResult


# --- valid JSON ----------------------------------------------------------


@pytest.mark.parametrize(
    "valid_json",
    [
        # Objects
        '{"key": "value"}',
        "{}",
        '{"nested": {"a": 1, "b": [2, 3]}}',
        '{"unicode": "héllo", "escape": "line1\\nline2"}',
        # Arrays
        "[]",
        "[1, 2, 3]",
        '["a", null, true, false]',
        '[{"a": 1}, {"b": 2}]',
        # Primitives (these are valid JSON per json.loads but easy to overlook)
        "5",
        "0",
        "-1",
        "3.14",
        "null",
        "true",
        "false",
        '"hello"',
        # Whitespace around the value is still valid JSON.
        '  {"k": 1}  ',
        "\n[1, 2]\n",
    ],
)
def test_is_json_score_one_for_valid_json(valid_json: str) -> None:
    """Valid JSON inputs (objects, arrays, primitives) score 1.0."""
    metric = IsJson(track=False)
    result = metric.score(output=valid_json)

    assert isinstance(result, ScoreResult)
    assert result.value == 1.0
    assert result.name == "is_json_metric"


# --- invalid JSON --------------------------------------------------------


@pytest.mark.parametrize(
    "invalid_json",
    [
        # Empty / whitespace-only
        "",
        " ",
        "\n",
        "\t  \n",
        # Malformed object / array
        "{",
        "}",
        "[",
        "]",
        "{,}",
        "{'key': 'value'}",  # single quotes are not valid JSON
        "{key: value}",  # unquoted keys
        '{"key": value}',  # unquoted value
        '{"unclosed": ',
        '{"trailing": ,}',
        # Numbers / literals with stray characters
        "5x",
        "true.",
        "null,",
        # Looks-like-JSON but is not
        "Not a JSON string",
        "undefined",
    ],
)
def test_is_json_score_zero_for_invalid_json(invalid_json: str) -> None:
    """Invalid JSON inputs (malformed, empty, garbage) score 0.0."""
    metric = IsJson(track=False)
    result = metric.score(output=invalid_json)

    assert isinstance(result, ScoreResult)
    assert result.value == 0.0
    assert result.name == "is_json_metric"


# --- non-string inputs ---------------------------------------------------


@pytest.mark.parametrize(
    "non_string_input",
    [
        None,
        0,
        1,
        -1,
        3.14,
    ],
)
def test_is_json_score_zero_for_non_string_inputs(non_string_input: object) -> None:
    """Non-string inputs (None, int, float) are caught by the metric's
    blanket `except Exception` and currently score 0.0. The test pins this
    current behaviour: a future change to raise or to stringify the input
    would be a deliberate break in the public contract, not a silent drift.
    """
    metric = IsJson(track=False)
    result = metric.score(output=non_string_input)

    assert isinstance(result, ScoreResult)
    assert result.value == 0.0
    assert result.name == "is_json_metric"


# --- name propagation ----------------------------------------------------


def test_is_json_default_metric_name() -> None:
    """The default metric name is `is_json_metric` and propagates to the
    `ScoreResult.name` field, which downstream consumers key on for grouping
    in the Opik UI."""
    metric = IsJson(track=False)
    result = metric.score(output='{"a": 1}')

    assert result.name == "is_json_metric"
    assert result.value == 1.0


def test_is_json_custom_metric_name_propagates() -> None:
    """A custom `name` argument on the metric is reflected in every
    `ScoreResult`, for both the pass and fail paths."""
    metric = IsJson(name="custom_json_check", track=False)

    pass_result = metric.score(output='{"a": 1}')
    fail_result = metric.score(output="not json")

    assert pass_result.name == "custom_json_check"
    assert pass_result.value == 1.0
    assert fail_result.name == "custom_json_check"
    assert fail_result.value == 0.0


# --- kwargs handling -----------------------------------------------------


def test_is_json_ignores_extra_keyword_arguments() -> None:
    """`IsJson.score` accepts `**ignored_kwargs`; extra keyword arguments
    must not raise and must not influence the score."""
    metric = IsJson(track=False)
    result = metric.score(
        output='{"a": 1}',
        ignored_kwarg_one="x",
        ignored_kwarg_two=42,
        ignored_kwarg_three=None,
    )

    assert result.value == 1.0
    assert result.name == "is_json_metric"


# --- score contract sanity ----------------------------------------------


def test_is_json_score_returns_score_result_instance() -> None:
    """`score()` always returns a `ScoreResult`, never None, a plain number,
    or a tuple, regardless of whether the input is valid JSON."""
    metric = IsJson(track=False)

    for value in ['{"a": 1}', "not json", "", "5", None]:
        result = metric.score(output=value)
        assert isinstance(result, ScoreResult)
