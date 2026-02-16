import pytest
import json
from opik.api_objects.opik_query_language import OpikQueryLanguage


# ============================================================
# Trace OQL tests
# ============================================================


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
            'id starts_with "123456" and id ends_with "789012"',
            [
                {"field": "id", "operator": "starts_with", "value": "123456"},
                {"field": "id", "operator": "ends_with", "value": "789012"},
            ],
        ),
        # Trace-specific fields
        (
            'thread_id = "thread_123"',
            [{"field": "thread_id", "operator": "=", "value": "thread_123"}],
        ),
        (
            'guardrails contains "safety"',
            [{"field": "guardrails", "operator": "contains", "value": "safety"}],
        ),
        (
            "llm_span_count > 5",
            [{"field": "llm_span_count", "operator": ">", "value": "5"}],
        ),
        (
            "created_at > 1234567890",
            [{"field": "created_at", "operator": ">", "value": "1234567890"}],
        ),
        (
            "last_updated_at <= 9876543210",
            [{"field": "last_updated_at", "operator": "<=", "value": "9876543210"}],
        ),
        (
            'experiment_id = "exp_001"',
            [{"field": "experiment_id", "operator": "=", "value": "exp_001"}],
        ),
        (
            "span_feedback_scores.accuracy > 0.9",
            [
                {
                    "field": "span_feedback_scores",
                    "key": "accuracy",
                    "operator": ">",
                    "value": "0.9",
                }
            ],
        ),
        (
            "span_feedback_scores.my_metric is_empty",
            [
                {
                    "field": "span_feedback_scores",
                    "key": "my_metric",
                    "operator": "is_empty",
                    "value": "",
                }
            ],
        ),
        (
            'annotation_queue_ids contains "queue_1"',
            [
                {
                    "field": "annotation_queue_ids",
                    "operator": "contains",
                    "value": "queue_1",
                }
            ],
        ),
        (
            "total_estimated_cost > 0 AND duration > 100",
            [
                {"field": "total_estimated_cost", "operator": ">", "value": "0"},
                {"field": "duration", "operator": ">", "value": "100"},
            ],
        ),
        (
            'input_json.model = "gpt-4"',
            [
                {
                    "field": "input_json",
                    "key": "model",
                    "operator": "=",
                    "value": "gpt-4",
                }
            ],
        ),
        (
            'output_json.result contains "success"',
            [
                {
                    "field": "output_json",
                    "key": "result",
                    "operator": "contains",
                    "value": "success",
                }
            ],
        ),
        # is_empty/is_not_empty operators
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
        # error_info operators
        (
            "error_info is_empty",
            [{"field": "error_info", "operator": "is_empty", "value": ""}],
        ),
        (
            "error_info is_not_empty",
            [{"field": "error_info", "operator": "is_not_empty", "value": ""}],
        ),
        # tags with list operators
        (
            "tags is_empty",
            [{"field": "tags", "operator": "is_empty", "value": ""}],
        ),
        (
            "tags is_not_empty",
            [{"field": "tags", "operator": "is_not_empty", "value": ""}],
        ),
    ],
)
def test_trace_oql__valid_filters(filter_string, expected):
    oql = OpikQueryLanguage.for_traces(filter_string)
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
        (
            'name = "test" and name = "other" extra_stuff',
            r"Invalid filter string, trailing characters.*",
        ),
        (
            'name = "test" OR name = "other"',
            r"Invalid filter string, OR is not currently supported",
        ),
        (
            'error_info = "something"',
            r"Operator = is not supported for field error_info.*",
        ),
    ],
)
def test_trace_oql__invalid_filters(filter_string, error_pattern):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage.for_traces(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_trace_oql__empty_filter(filter_string):
    oql = OpikQueryLanguage.for_traces(filter_string)
    assert oql.parsed_filters is None


# ============================================================
# Span OQL tests
# ============================================================


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
        # Span-specific fields
        (
            'type = "llm"',
            [{"field": "type", "operator": "=", "value": "llm", "type": "enum"}],
        ),
        (
            'model = "gpt-4"',
            [{"field": "model", "operator": "=", "value": "gpt-4"}],
        ),
        (
            'provider = "openai"',
            [{"field": "provider", "operator": "=", "value": "openai"}],
        ),
        (
            'trace_id = "trace-456"',
            [{"field": "trace_id", "operator": "=", "value": "trace-456"}],
        ),
        (
            'model contains "gpt" AND provider = "openai"',
            [
                {"field": "model", "operator": "contains", "value": "gpt"},
                {"field": "provider", "operator": "=", "value": "openai"},
            ],
        ),
        (
            'type = "llm" AND total_estimated_cost > 0',
            [
                {"field": "type", "operator": "=", "value": "llm", "type": "enum"},
                {"field": "total_estimated_cost", "operator": ">", "value": "0"},
            ],
        ),
        (
            'type = "llm" AND total_estimated_cost > 0 AND provider = "openai"',
            [
                {"field": "type", "operator": "=", "value": "llm", "type": "enum"},
                {"field": "total_estimated_cost", "operator": ">", "value": "0"},
                {"field": "provider", "operator": "=", "value": "openai"},
            ],
        ),
        # Shared fields that also exist in spans
        (
            'id starts_with "123456" and id ends_with "789012"',
            [
                {"field": "id", "operator": "starts_with", "value": "123456"},
                {"field": "id", "operator": "ends_with", "value": "789012"},
            ],
        ),
        (
            "duration > 100",
            [{"field": "duration", "operator": ">", "value": "100"}],
        ),
        (
            'metadata.key = "value"',
            [
                {
                    "field": "metadata",
                    "key": "key",
                    "operator": "=",
                    "value": "value",
                }
            ],
        ),
        (
            'input_json.model = "gpt-4"',
            [
                {
                    "field": "input_json",
                    "key": "model",
                    "operator": "=",
                    "value": "gpt-4",
                }
            ],
        ),
        (
            'output_json.result contains "success"',
            [
                {
                    "field": "output_json",
                    "key": "result",
                    "operator": "contains",
                    "value": "success",
                }
            ],
        ),
        # error_info
        (
            "error_info is_empty",
            [{"field": "error_info", "operator": "is_empty", "value": ""}],
        ),
        (
            "error_info is_not_empty",
            [{"field": "error_info", "operator": "is_not_empty", "value": ""}],
        ),
        # is_empty/is_not_empty for feedback_scores in spans
        (
            "feedback_scores.accuracy is_empty",
            [
                {
                    "field": "feedback_scores",
                    "key": "accuracy",
                    "operator": "is_empty",
                    "value": "",
                }
            ],
        ),
        (
            "feedback_scores.accuracy is_not_empty AND feedback_scores.accuracy > 0.8",
            [
                {
                    "field": "feedback_scores",
                    "key": "accuracy",
                    "operator": "is_not_empty",
                    "value": "",
                },
                {
                    "field": "feedback_scores",
                    "key": "accuracy",
                    "operator": ">",
                    "value": "0.8",
                },
            ],
        ),
        # tags operators
        (
            "tags is_empty",
            [{"field": "tags", "operator": "is_empty", "value": ""}],
        ),
        (
            "tags is_not_empty",
            [{"field": "tags", "operator": "is_not_empty", "value": ""}],
        ),
    ],
)
def test_span_oql__valid_filters(filter_string, expected):
    oql = OpikQueryLanguage.for_spans(filter_string)
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
            'name = "test" OR name = "other"',
            r"Invalid filter string, OR is not currently supported",
        ),
        (
            'error_info = "something"',
            r"Operator = is not supported for field error_info.*",
        ),
        (
            'type contains "llm"',
            r"Operator contains is not supported for field type.*",
        ),
        (
            # span_feedback_scores is not a dictionary field in SpanOQLConfig
            "span_feedback_scores.accuracy > 0.5",
            r"Field span_feedback_scores\.accuracy is not supported.*",
        ),
    ],
)
def test_span_oql__invalid_filters(filter_string, error_pattern):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage.for_spans(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_span_oql__empty_filter(filter_string):
    oql = OpikQueryLanguage.for_spans(filter_string)
    assert oql.parsed_filters is None


# ============================================================
# Trace thread OQL tests
# ============================================================


@pytest.mark.parametrize(
    "filter_string, expected",
    [
        (
            'id = "thread-123"',
            [{"field": "id", "operator": "=", "value": "thread-123"}],
        ),
        (
            'first_message contains "hello"',
            [{"field": "first_message", "operator": "contains", "value": "hello"}],
        ),
        (
            'last_message contains "goodbye"',
            [{"field": "last_message", "operator": "contains", "value": "goodbye"}],
        ),
        (
            "number_of_messages > 5",
            [{"field": "number_of_messages", "operator": ">", "value": "5"}],
        ),
        (
            "duration >= 1000",
            [{"field": "duration", "operator": ">=", "value": "1000"}],
        ),
        (
            "created_at > 1234567890",
            [{"field": "created_at", "operator": ">", "value": "1234567890"}],
        ),
        (
            "last_updated_at <= 9876543210",
            [{"field": "last_updated_at", "operator": "<=", "value": "9876543210"}],
        ),
        (
            "start_time > 1234567890",
            [{"field": "start_time", "operator": ">", "value": "1234567890"}],
        ),
        (
            "end_time < 9876543210",
            [{"field": "end_time", "operator": "<", "value": "9876543210"}],
        ),
        (
            "feedback_scores.accuracy > 0.8",
            [
                {
                    "field": "feedback_scores",
                    "key": "accuracy",
                    "operator": ">",
                    "value": "0.8",
                }
            ],
        ),
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
            'status = "completed"',
            [
                {
                    "field": "status",
                    "operator": "=",
                    "value": "completed",
                    "type": "enum",
                }
            ],
        ),
        (
            'tags contains "important"',
            [{"field": "tags", "operator": "contains", "value": "important"}],
        ),
        (
            "tags is_empty",
            [{"field": "tags", "operator": "is_empty", "value": ""}],
        ),
        (
            'annotation_queue_ids contains "queue_1"',
            [
                {
                    "field": "annotation_queue_ids",
                    "operator": "contains",
                    "value": "queue_1",
                }
            ],
        ),
        # AND combinations
        (
            "number_of_messages > 3 AND duration > 500",
            [
                {"field": "number_of_messages", "operator": ">", "value": "3"},
                {"field": "duration", "operator": ">", "value": "500"},
            ],
        ),
        (
            'status = "active" AND tags contains "vip" AND feedback_scores.quality is_not_empty',
            [
                {"field": "status", "operator": "=", "value": "active", "type": "enum"},
                {"field": "tags", "operator": "contains", "value": "vip"},
                {
                    "field": "feedback_scores",
                    "key": "quality",
                    "operator": "is_not_empty",
                    "value": "",
                },
            ],
        ),
    ],
)
def test_trace_thread_oql__valid_filters(filter_string, expected):
    oql = OpikQueryLanguage.for_threads(filter_string)
    parsed = json.loads(oql.parsed_filters)
    assert len(parsed) == len(expected)

    for i, line in enumerate(expected):
        for key, value in line.items():
            assert parsed[i][key] == value


@pytest.mark.parametrize(
    "filter_string, error_pattern",
    [
        ("name = test", r"Invalid value.*"),
        (
            'status contains "active"',
            r"Operator contains is not supported for field status.*",
        ),
        (
            'id = "test" OR id = "other"',
            r"Invalid filter string, OR is not currently supported",
        ),
        (
            'id = "test" extra_stuff',
            r"Invalid filter string, trailing characters.*",
        ),
        (
            # metadata is not a dictionary field in ThreadOQLConfig
            'metadata.key = "value"',
            r"Field metadata\.key is not supported.*",
        ),
    ],
)
def test_trace_thread_oql__invalid_filters(filter_string, error_pattern):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage.for_threads(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_trace_thread_oql__empty_filter(filter_string):
    oql = OpikQueryLanguage.for_threads(filter_string)
    assert oql.parsed_filters is None


# ============================================================
# Dataset item OQL tests
# ============================================================


@pytest.mark.parametrize(
    "filter_string, expected",
    [
        ('id = "item-123"', [{"field": "id", "operator": "=", "value": "item-123"}]),
        (
            'full_data contains "search_term"',
            [{"field": "full_data", "operator": "contains", "value": "search_term"}],
        ),
        (
            'source = "manual"',
            [{"field": "source", "operator": "=", "value": "manual"}],
        ),
        (
            'trace_id = "trace-456"',
            [{"field": "trace_id", "operator": "=", "value": "trace-456"}],
        ),
        (
            'span_id = "span-789"',
            [{"field": "span_id", "operator": "=", "value": "span-789"}],
        ),
        (
            'tags contains "important"',
            [{"field": "tags", "operator": "contains", "value": "important"}],
        ),
        (
            "tags is_empty",
            [{"field": "tags", "operator": "is_empty", "value": ""}],
        ),
        (
            "tags is_not_empty",
            [{"field": "tags", "operator": "is_not_empty", "value": ""}],
        ),
        (
            'created_by = "user@example.com"',
            [{"field": "created_by", "operator": "=", "value": "user@example.com"}],
        ),
        (
            'last_updated_by != "admin@example.com"',
            [
                {
                    "field": "last_updated_by",
                    "operator": "!=",
                    "value": "admin@example.com",
                }
            ],
        ),
        (
            "created_at > 1234567890",
            [{"field": "created_at", "operator": ">", "value": "1234567890"}],
        ),
        (
            "last_updated_at <= 9876543210",
            [{"field": "last_updated_at", "operator": "<=", "value": "9876543210"}],
        ),
        (
            'data.input = "test query"',
            [
                {
                    "field": "data",
                    "key": "input",
                    "operator": "=",
                    "value": "test query",
                }
            ],
        ),
        (
            'data.expected_output contains "answer"',
            [
                {
                    "field": "data",
                    "key": "expected_output",
                    "operator": "contains",
                    "value": "answer",
                }
            ],
        ),
        (
            'data."complex key" = "value"',
            [
                {
                    "field": "data",
                    "key": "complex key",
                    "operator": "=",
                    "value": "value",
                }
            ],
        ),
        (
            'data."Escaped ""Quote""" != "test"',
            [
                {
                    "field": "data",
                    "key": 'Escaped "Quote"',
                    "operator": "!=",
                    "value": "test",
                }
            ],
        ),
        (
            'id starts_with "item-" AND source = "manual"',
            [
                {"field": "id", "operator": "starts_with", "value": "item-"},
                {"field": "source", "operator": "=", "value": "manual"},
            ],
        ),
        (
            'data.input contains "query" AND data.expected_output contains "answer"',
            [
                {
                    "field": "data",
                    "key": "input",
                    "operator": "contains",
                    "value": "query",
                },
                {
                    "field": "data",
                    "key": "expected_output",
                    "operator": "contains",
                    "value": "answer",
                },
            ],
        ),
        (
            'tags contains "test" AND created_at > 1234567890 AND source = "api"',
            [
                {"field": "tags", "operator": "contains", "value": "test"},
                {"field": "created_at", "operator": ">", "value": "1234567890"},
                {"field": "source", "operator": "=", "value": "api"},
            ],
        ),
        (
            'full_data contains "important" AND tags is_not_empty',
            [
                {"field": "full_data", "operator": "contains", "value": "important"},
                {"field": "tags", "operator": "is_not_empty", "value": ""},
            ],
        ),
        (
            'data.category = "test" AND data.subcategory != "excluded"',
            [
                {
                    "field": "data",
                    "key": "category",
                    "operator": "=",
                    "value": "test",
                },
                {
                    "field": "data",
                    "key": "subcategory",
                    "operator": "!=",
                    "value": "excluded",
                },
            ],
        ),
    ],
)
def test_dataset_item_oql__valid_filters__happyflow(filter_string, expected):
    oql = OpikQueryLanguage.for_dataset_items(filter_string)
    parsed = json.loads(oql.parsed_filters)
    assert len(parsed) == len(expected)

    for i, line in enumerate(expected):
        for key, value in line.items():
            assert parsed[i][key] == value


@pytest.mark.parametrize(
    "filter_string, error_pattern",
    [
        (
            'metadata.key = "value"',
            r"Field metadata\.key is not supported.*",
        ),
        (
            "feedback_scores.score > 0.5",
            r"Field feedback_scores\.score is not supported.*",
        ),
        (
            "created_at contains 123",
            r"Operator contains is not supported for field created_at.*",
        ),
        (
            "tags > 5",
            r"Operator > is not supported for field tags.*",
        ),
        (
            "data > 5",
            r"Operator > is not supported for field data.*",
        ),
        (
            'id = "test" OR source = "manual"',
            r"Invalid filter string, OR is not currently supported",
        ),
        (
            'source = "test" extra_stuff',
            r"Invalid filter string, trailing characters.*",
        ),
    ],
)
def test_dataset_item_oql__invalid_filters__raises_value_error(
    filter_string, error_pattern
):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage.for_dataset_items(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_dataset_item_oql__empty_filter__returns_none(filter_string):
    oql = OpikQueryLanguage.for_dataset_items(filter_string)
    assert oql.parsed_filters is None


@pytest.mark.parametrize(
    "filter_string, expected",
    [
        (
            'id != "1234"',
            [{"field": "id", "operator": "!=", "value": "1234"}],
        ),
        (
            'commit = "abc123"',
            [{"field": "commit", "operator": "=", "value": "abc123"}],
        ),
        (
            'template contains "hello"',
            [{"field": "template", "operator": "contains", "value": "hello"}],
        ),
        (
            'change_description not_contains "fix"',
            [
                {
                    "field": "change_description",
                    "operator": "not_contains",
                    "value": "fix",
                }
            ],
        ),
        (
            'metadata.environment >= "prod"',
            [
                {
                    "field": "metadata",
                    "key": "environment",
                    "operator": ">=",
                    "value": "prod",
                }
            ],
        ),
        (
            'type = "MUSTACHE"',
            [{"field": "type", "operator": "=", "value": "MUSTACHE"}],
        ),
        (
            'tags contains "production"',
            [{"field": "tags", "operator": "contains", "value": "production"}],
        ),
        (
            "created_at > 1234567890",
            [{"field": "created_at", "operator": ">", "value": "1234567890"}],
        ),
        (
            'created_by ends_with "user@example.com"',
            [
                {
                    "field": "created_by",
                    "operator": "ends_with",
                    "value": "user@example.com",
                }
            ],
        ),
        (
            'template starts_with "customer" AND tags contains "production"',
            [
                {"field": "template", "operator": "starts_with", "value": "customer"},
                {"field": "tags", "operator": "contains", "value": "production"},
            ],
        ),
    ],
)
def test_prompt_version_oql__valid_filters__happyflow(filter_string, expected):
    oql = OpikQueryLanguage.for_prompt_versions(filter_string)
    parsed = json.loads(oql.parsed_filters)
    assert len(parsed) == len(expected)

    for i, line in enumerate(expected):
        for key, value in line.items():
            assert parsed[i][key] == value


@pytest.mark.parametrize(
    "filter_string, error_pattern",
    [
        (
            "tags > 5",
            r"Operator > is not supported for field tags.*",
        ),
        (
            'type contains "MUSTACHE"',
            r"Operator contains is not supported for field type.*",
        ),
        (
            'id = "test" OR commit = "abc"',
            r"Invalid filter string, OR is not currently supported",
        ),
        (
            'template = "test" extra_stuff',
            r"Invalid filter string, trailing characters.*",
        ),
    ],
)
def test_prompt_version_oql__invalid_filters__raises_value_error(
    filter_string, error_pattern
):
    with pytest.raises(ValueError, match=error_pattern):
        OpikQueryLanguage.for_prompt_versions(filter_string)


@pytest.mark.parametrize("filter_string", [None, ""])
def test_prompt_version_oql__empty_filter__returns_none(filter_string):
    oql = OpikQueryLanguage.for_prompt_versions(filter_string)
    assert oql.parsed_filters is None
