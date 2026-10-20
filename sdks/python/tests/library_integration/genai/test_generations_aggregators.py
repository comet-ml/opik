from google.genai import types as genai_types

from opik.integrations.genai import generations_aggregators


def _chunk(parts, finish_reason=None, usage=None):
    return genai_types.GenerateContentResponse(
        candidates=[
            genai_types.Candidate(
                content=genai_types.Content(role="model", parts=parts),
                finish_reason=finish_reason,
            )
        ],
        usage_metadata=usage,
    )


def _call(city):
    return genai_types.Part(
        function_call=genai_types.FunctionCall(name="get_weather", args={"city": city})
    )


def _usage():
    return genai_types.GenerateContentResponseUsageMetadata(
        prompt_token_count=3, candidates_token_count=5, total_token_count=8
    )


def test_aggregate__text_chunks__joined_into_one_part():
    items = [
        _chunk([genai_types.Part(text="Hello, ")]),
        _chunk([genai_types.Part(text="world")], finish_reason="STOP", usage=_usage()),
    ]

    aggregated = generations_aggregators.aggregate_response_content_items(items)

    parts = aggregated.candidates[0].content.parts
    assert [part.text for part in parts] == ["Hello, world"]
    assert aggregated.candidates[0].finish_reason == genai_types.FinishReason.STOP
    assert aggregated.usage_metadata.total_token_count == 8


def test_aggregate__parallel_function_calls_in_separate_chunks__all_kept():
    items = [
        _chunk([_call("Paris")]),
        _chunk([_call("Tokyo")], finish_reason="STOP", usage=_usage()),
    ]

    aggregated = generations_aggregators.aggregate_response_content_items(items)

    parts = aggregated.candidates[0].content.parts
    assert [part.function_call.args for part in parts] == [
        {"city": "Paris"},
        {"city": "Tokyo"},
    ]
    assert all(part.text is None for part in parts)


def test_aggregate__text_before_function_call__both_kept():
    items = [
        _chunk([genai_types.Part(text="Let me check. ")]),
        _chunk([genai_types.Part(text="One moment.")]),
        _chunk([_call("Paris")], finish_reason="STOP"),
    ]

    aggregated = generations_aggregators.aggregate_response_content_items(items)

    parts = aggregated.candidates[0].content.parts
    assert parts[0].text == "Let me check. One moment."
    assert parts[0].function_call is None
    assert parts[1].function_call.args == {"city": "Paris"}
    assert parts[1].text is None


def test_aggregate__last_chunk_has_only_usage__text_and_usage_kept():
    items = [
        _chunk([genai_types.Part(text="Hi")], finish_reason="STOP"),
        genai_types.GenerateContentResponse(usage_metadata=_usage()),
    ]

    aggregated = generations_aggregators.aggregate_response_content_items(items)

    assert aggregated.candidates[0].content.parts[0].text == "Hi"
    assert aggregated.usage_metadata.total_token_count == 8


def test_aggregate__function_call_part_with_empty_text__kept():
    part = genai_types.Part(
        text="", function_call=genai_types.FunctionCall(name="get_weather", args={})
    )
    items = [
        _chunk([genai_types.Part(text="Hi")]),
        _chunk([part], finish_reason="STOP", usage=_usage()),
    ]

    aggregated = generations_aggregators.aggregate_response_content_items(items)

    parts = aggregated.candidates[0].content.parts
    assert parts[0].text == "Hi"
    assert parts[1].function_call.name == "get_weather"
