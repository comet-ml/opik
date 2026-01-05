import pytest
import json
from opik.api_objects.opik_query_language import OpikQueryLanguage


@pytest.mark.parametrize(
    "filter_string, expected",
    [
        ('name = "test"', [{"field": "name", "operator": "=", "value": "test"}]),
        (
            "usage.total_tokens > 100",
            [{"field": "usage.total_tokens", "operator": ">", "value": "100"}],
        ),
        (
            'tags contains "important"',
            [{"field": "tags", "operator": "contains", "value": "important"}],
        ),
        (
            'output not_contains "error"',
            [{"field": "output", "operator": "not_contains", "value": "error"}],
        ),
        (
            "feedback_scores >= 4.5",
            [{"field": "feedback_scores", "operator": ">=", "value": "4.5"}],
        ),
        # New test cases for quoted identifiers
        (
            'feedback_scores."Answer Relevance" < 0.8',
            [
                {
                    "field": "feedback_scores",
                    "key": "Answer Relevance",
                    "operator": "<",
                    "value": "0.8",
                }
            ],
        ),
        (
            'feedback_scores."Escaped ""Quote""" < 0.8',
            [
                {
                    "field": "feedback_scores",
                    "key": 'Escaped "Quote"',
                    "operator": "<",
                    "value": "0.8",
                }
            ],
        ),
        # Test cases for AND:
        (
            'name = "test" AND tags contains "important"  ',
            [
                {"field": "name", "operator": "=", "value": "test"},
                {"field": "tags", "operator": "contains", "value": "important"},
            ],
        ),
        (
            'feedback_scores."Escaped ""Quote""" < 0.8 and feedback_scores >= 4.5',
            [
                {
                    "field": "feedback_scores",
                    "key": 'Escaped "Quote"',
                    "operator": "<",
                    "value": "0.8",
                },
                {"field": "feedback_scores", "operator": ">=", "value": "4.5"},
            ],
        ),
        (
            'type="llm" and total_estimated_cost>0',
            [
                {"field": "type", "operator": "=", "value": "llm"},
                {"field": "total_estimated_cost", "operator": ">", "value": "0"},
            ],
        ),
        (
            'type="llm" and total_estimated_cost>0 and provider="openai"',
            [
                {"field": "type", "operator": "=", "value": "llm"},
                {"field": "total_estimated_cost", "operator": ">", "value": "0"},
                {"field": "provider", "operator": "=", "value": "openai"},
            ],
        ),
        (
            'id starts_with "123456" and id ends_with "789012"',
            [
                {"field": "id", "operator": "starts_with", "value": "123456"},
                {"field": "id", "operator": "ends_with", "value": "789012"},
            ],
        ),
        # Test cases for is_empty and is_not_empty operators
        (
            "feedback_scores.my_metric is_empty",
            [
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": "is_empty",
                    "value": "",
                }
            ],
        ),
        (
            "feedback_scores.my_metric is_not_empty",
            [
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": "is_not_empty",
                    "value": "",
                }
            ],
        ),
        (
            'feedback_scores."Answer Relevance" is_empty',
            [
                {
                    "field": "feedback_scores",
                    "key": "Answer Relevance",
                    "operator": "is_empty",
                    "value": "",
                }
            ],
        ),
        (
            "feedback_scores.my_metric is_empty AND feedback_scores.other_metric is_not_empty",
            [
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": "is_empty",
                    "value": "",
                },
                {
                    "field": "feedback_scores",
                    "key": "other_metric",
                    "operator": "is_not_empty",
                    "value": "",
                },
            ],
        ),
        # Test cases mixing is_empty/is_not_empty with regular operators
        (
            "feedback_scores.my_metric is_empty AND feedback_scores.other_metric > 0.5",
            [
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": "is_empty",
                    "value": "",
                },
                {
                    "field": "feedback_scores",
                    "key": "other_metric",
                    "operator": ">",
                    "value": "0.5",
                },
            ],
        ),
        (
            "feedback_scores.accuracy > 0.8 AND feedback_scores.user_frustration is_empty",
            [
                {
                    "field": "feedback_scores",
                    "key": "accuracy",
                    "operator": ">",
                    "value": "0.8",
                },
                {
                    "field": "feedback_scores",
                    "key": "user_frustration",
                    "operator": "is_empty",
                    "value": "",
                },
            ],
        ),
        (
            "feedback_scores.my_metric is_not_empty AND feedback_scores.my_metric >= 0.5",
            [
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": "is_not_empty",
                    "value": "",
                },
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": ">=",
                    "value": "0.5",
                },
            ],
        ),
        (
            'feedback_scores."Answer Relevance" is_empty AND feedback_scores."Answer Relevance" < 0.5',
            [
                {
                    "field": "feedback_scores",
                    "key": "Answer Relevance",
                    "operator": "is_empty",
                    "value": "",
                },
                {
                    "field": "feedback_scores",
                    "key": "Answer Relevance",
                    "operator": "<",
                    "value": "0.5",
                },
            ],
        ),
        (
            "feedback_scores.accuracy >= 0.7 AND feedback_scores.relevance is_not_empty AND feedback_scores.quality > 0.5",
            [
                {
                    "field": "feedback_scores",
                    "key": "accuracy",
                    "operator": ">=",
                    "value": "0.7",
                },
                {
                    "field": "feedback_scores",
                    "key": "relevance",
                    "operator": "is_not_empty",
                    "value": "",
                },
                {
                    "field": "feedback_scores",
                    "key": "quality",
                    "operator": ">",
                    "value": "0.5",
                },
            ],
        ),
        (
            'name = "test" AND feedback_scores.my_metric is_empty AND duration > 100',
            [
                {"field": "name", "operator": "=", "value": "test"},
                {
                    "field": "feedback_scores",
                    "key": "my_metric",
                    "operator": "is_empty",
                    "value": "",
                },
                {"field": "duration", "operator": ">", "value": "100"},
            ],
        ),
    ],
)
def test_valid_oql_expressions(filter_string, expected):
    oql = OpikQueryLanguage(filter_string)
    parsed = json.loads(oql.parsed_filters)
    assert len(parsed) == len(expected)

    for i, line in enumerate(expected):
        for key, value in line.items():
            assert parsed[i][key] == value


@pytest.mark.parametrize(
    "filter_string, error_pattern",
    [
        ('invalid_field.key = "value"', r"Field invalid_field\.key is not supported.*"),
        ("name = test", r"Invalid value.*"),
        (
            "usage.invalid_metric = 100",
            r"When querying usage, invalid_metric is not supported.*",
        ),
        ('name = "test" extra_stuff', r"Invalid filter string, trailing characters.*"),
        (
            'feedback_scores."Unterminated Quote < 0.8',
            r'Missing closing quote for: "Unterminated Quote < 0.8',
        ),
        # Tests with AND
        (
            'name = "test" and name = "other" extra_stuff',
            r"Invalid filter string, trailing characters.*",
        ),
        (
            'name = "test" OR name = "other"',
            r"Invalid filter string, OR is not currently supported",
        ),
    ],
)
def test_invalid_oql_expressions(filter_string, error_pattern):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_empty_filter(filter_string):
    oql = OpikQueryLanguage(filter_string)
    assert oql.parsed_filters is None
