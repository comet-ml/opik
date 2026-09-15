import asyncio
from typing import Any

import pytest

from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.heuristics import is_json


@pytest.mark.parametrize(
    "valid_json_str",
    [
        # JSON Objects
        '{"key": "value"}',
        "{}",
        '{"name": "Alice", "age": 30, "is_admin": true, "skills": ["python", "c++"], "nested": {"a": 1, "b": null}}',
        '  { "spaces" : "are fine" }  ',
        # JSON Arrays
        "[]",
        "[1, 2, 3]",
        '["apple", "banana", "cherry"]',
        '[true, false, null, 100, 3.14, "text", {}, [1, 2]]',
        "  [ 1 , 2 , 3 ]  ",
        # JSON Primitives - Numbers
        "5",
        "0",
        "-42",
        "3.14159",
        "1e-5",
        "-2.5e+3",
        # JSON Primitives - Null & Booleans
        "null",
        "true",
        "false",
        # JSON Primitives - Strings (valid JSON strings are double-quoted)
        '"hello"',
        '"5"',
        '"null"',
        '"true"',
        '""',
        r'"with \"escaped\" quotes"',
        r'"line1\nline2"',
        '"   "',
    ],
)
def test_is_json__valid_json_inputs__returns_score_one(valid_json_str: str) -> None:
    metric = is_json.IsJson(track=False)
    result = metric.score(output=valid_json_str)

    assert isinstance(result, score_result.ScoreResult)
    assert result.name == "is_json_metric"
    assert result.value == 1.0
    assert result.reason is None
    assert result.metadata is None


@pytest.mark.parametrize(
    "malformed_or_empty_str",
    [
        # Empty and whitespace strings
        "",
        " ",
        "   \t\n  ",
        # Unquoted strings / plain text (not valid JSON without double quotes)
        "hello",
        "Not a JSON string",
        "foo bar",
        # Single-quoted strings (invalid in standard JSON)
        "'hello'",
        "{'key': 'value'}",
        # Python-capitalized literals
        "True",
        "False",
        "None",
        # Syntax errors - trailing commas
        '{"key": "value",}',
        "[1, 2,]",
        # Syntax errors - unquoted keys or missing values
        '{key: "value"}',
        '{ "key": }',
        # Syntax errors - unclosed or mismatched delimiters
        '{ "key": "value"',
        "[1, 2, 3",
        '{ "key": "value" ]',
        "[1, 2, 3}",
        # Non-string object keys
        '{ 123: "value" }',
        # Unsupported JavaScript literals
        "undefined",
        # Malformed single tokens
        "{",
        "}",
        "[",
        "]",
        '"',
    ],
)
def test_is_json__malformed_or_empty_strings__returns_score_zero(
    malformed_or_empty_str: str,
) -> None:
    metric = is_json.IsJson(track=False)
    result = metric.score(output=malformed_or_empty_str)

    assert isinstance(result, score_result.ScoreResult)
    assert result.name == "is_json_metric"
    assert result.value == 0.0
    assert result.reason is None
    assert result.metadata is None


@pytest.mark.parametrize(
    "non_string_input",
    [
        None,
        5,
        0,
        -42,
        3.14,
        True,
        False,
        {"key": "value"},
        {},
        [1, 2, 3],
        [],
        (1, 2, 3),
        {1, 2, 3},
        object(),
    ],
)
def test_is_json__non_string_inputs__returns_score_zero(
    non_string_input: Any,
) -> None:
    metric = is_json.IsJson(track=False)
    result = metric.score(output=non_string_input)

    assert isinstance(result, score_result.ScoreResult)
    assert result.name == "is_json_metric"
    assert result.value == 0.0
    assert result.reason is None
    assert result.metadata is None


def test_is_json__custom_name__uses_custom_name() -> None:
    metric = is_json.IsJson(name="my_custom_json_check", track=False)

    assert metric.name == "my_custom_json_check"
    assert metric.track is False

    result_valid = metric.score('{"a": 1}')
    assert result_valid.name == "my_custom_json_check"
    assert result_valid.value == 1.0

    result_invalid = metric.score("not-json")
    assert result_invalid.name == "my_custom_json_check"
    assert result_invalid.value == 0.0


def test_is_json__ignored_kwargs__scores_successfully() -> None:
    metric = is_json.IsJson(track=False)
    result = metric.score(
        output='{"success": true}',
        reference="ignored_reference",
        some_extra_arg="extra_value",
    )

    assert result.name == "is_json_metric"
    assert result.value == 1.0


def test_is_json__ascore__computes_score_asynchronously() -> None:
    metric = is_json.IsJson(track=False)

    result_valid = asyncio.run(metric.ascore(output='{"async": true}'))
    assert result_valid.name == "is_json_metric"
    assert result_valid.value == 1.0

    result_invalid = asyncio.run(metric.ascore(output="invalid json"))
    assert result_invalid.name == "is_json_metric"
    assert result_invalid.value == 0.0


def test_is_json__public_api_import() -> None:
    from opik.evaluation.metrics import IsJson

    metric = IsJson(track=False)
    result = metric.score('{"accessible": true}')
    assert result.value == 1.0
