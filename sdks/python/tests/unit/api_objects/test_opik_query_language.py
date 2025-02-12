import pytest
import json
from opik.api_objects.opik_query_language import OpikQueryLanguage


@pytest.mark.parametrize(
    "filter_string, expected",
    [
        ('name = "test"', {"field": "name", "operator": "=", "value": "test"}),
        (
            "usage.total_tokens > 100",
            {"field": "usage.total_tokens", "operator": ">", "value": "100"},
        ),
        (
            'tags contains "important"',
            {"field": "tags", "operator": "contains", "value": "important"},
        ),
        (
            'output not_contains "error"',
            {"field": "output", "operator": "not_contains", "value": "error"},
        ),
        (
            "feedback_scores >= 4.5",
            {"field": "feedback_scores", "operator": ">=", "value": "4.5"},
        ),
        # New test cases for quoted identifiers
        (
            'feedback_scores."Answer Relevance" < 0.8',
            {
                "field": "feedback_scores",
                "key": "Answer Relevance",
                "operator": "<",
                "value": "0.8",
            },
        ),
        (
            'feedback_scores."Escaped ""Quote""" < 0.8',
            {
                "field": "feedback_scores",
                "key": 'Escaped "Quote"',
                "operator": "<",
                "value": "0.8",
            },
        ),
    ],
)
def test_valid_oql_expressions(filter_string, expected):
    oql = OpikQueryLanguage(filter_string)
    parsed = json.loads(oql.parsed_filters)
    assert len(parsed) == 1
    parsed_result = parsed[0]

    for key, value in expected.items():
        assert parsed_result[key] == value


@pytest.mark.parametrize(
    "filter_string, error_pattern",
    [
        ('name > "test"', r"Operator > is not supported for field name.*"),
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
    ],
)
def test_invalid_oql_expressions(filter_string, error_pattern):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_empty_filter(filter_string):
    oql = OpikQueryLanguage(filter_string)
    assert oql.parsed_filters is None
