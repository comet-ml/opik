"""Unit tests for the Gemini (genai) streamed-response aggregator.

The aggregator folds the chunks of a ``generate_content_stream`` call back
into a single ``GenerateContentResponse`` that is logged as the span output.
"""

from google.genai import types as genai_types

from opik.integrations.genai.generations_aggregators import (
    aggregate_response_content_items,
)


def _response(parts=None, usage=None, model_version="gemini-2.0-flash"):
    candidate = {}
    if parts is not None:
        candidate = {
            "content": {"role": "model", "parts": parts},
            "finish_reason": "STOP",
        }
    payload = {"candidates": [candidate] if parts is not None else []}
    if usage is not None:
        payload["usage_metadata"] = usage
    if model_version is not None:
        payload["model_version"] = model_version
    return genai_types.GenerateContentResponse.model_validate(payload)


def _parts_of(response):
    return response.candidates[0].content.parts


def test_text_split_across_chunks_is_concatenated():
    aggregated = aggregate_response_content_items(
        [_response([{"text": "Hel"}]), _response([{"text": "lo"}])]
    )

    parts = _parts_of(aggregated)
    assert len(parts) == 1
    assert parts[0].text == "Hello"


def test_function_call_between_text_chunks_is_kept_in_order():
    aggregated = aggregate_response_content_items(
        [
            _response([{"text": "Checking "}]),
            _response(
                [
                    {
                        "function_call": {
                            "name": "get_weather",
                            "args": {"city": "Paris"},
                        }
                    }
                ]
            ),
            _response([{"text": "done"}]),
        ]
    )

    parts = _parts_of(aggregated)
    assert len(parts) == 3
    assert parts[0].text == "Checking "
    assert parts[1].function_call.name == "get_weather"
    assert dict(parts[1].function_call.args) == {"city": "Paris"}
    assert parts[2].text == "done"


def test_function_call_in_last_chunk_is_not_overwritten_by_text():
    aggregated = aggregate_response_content_items(
        [
            _response([{"text": "Hi"}]),
            _response(
                [
                    {
                        "function_call": {
                            "name": "get_weather",
                            "args": {"city": "Paris"},
                        }
                    }
                ]
            ),
        ]
    )

    parts = _parts_of(aggregated)
    assert len(parts) == 2
    assert parts[0].text == "Hi"
    assert parts[1].function_call.name == "get_weather"


def test_text_after_function_call_in_same_chunk_is_kept():
    aggregated = aggregate_response_content_items(
        [
            _response(
                [
                    {"text": "first "},
                    {
                        "function_call": {
                            "name": "get_weather",
                            "args": {"city": "Paris"},
                        }
                    },
                    {"text": "second"},
                ]
            )
        ]
    )

    parts = _parts_of(aggregated)
    assert len(parts) == 3
    assert parts[0].text == "first "
    assert parts[1].function_call.name == "get_weather"
    assert parts[2].text == "second"


def test_usage_only_last_chunk_does_not_drop_earlier_parts():
    aggregated = aggregate_response_content_items(
        [
            _response([{"text": "Hello"}]),
            _response(
                parts=None,
                usage={
                    "prompt_token_count": 10,
                    "candidates_token_count": 5,
                    "total_token_count": 15,
                },
            ),
        ]
    )

    parts = _parts_of(aggregated)
    assert len(parts) == 1
    assert parts[0].text == "Hello"
    assert aggregated.usage_metadata.total_token_count == 15


def test_metadata_comes_from_last_chunk():
    aggregated = aggregate_response_content_items(
        [
            _response([{"text": "a"}], model_version="gemini-2.0-flash"),
            _response([{"text": "b"}], model_version="gemini-2.5-flash"),
        ]
    )

    assert aggregated.model_version == "gemini-2.5-flash"
    assert _parts_of(aggregated)[0].text == "ab"
