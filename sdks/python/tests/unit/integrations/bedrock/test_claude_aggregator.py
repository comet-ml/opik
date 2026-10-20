import json
from typing import Any, Dict, List, Optional

import opik
from opik import llm_usage
from opik.integrations.bedrock.invoke_model import chunks_aggregator
from tests.conftest import random_chars


def _chunk(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


def _stream(
    message_start_usage: Dict[str, Any],
    invocation_metrics: Dict[str, Any],
    message_delta_usage: Optional[Dict[str, Any]] = None,
) -> List[Dict[str, Any]]:
    return [
        _chunk(
            {
                "type": "message_start",
                "message": {"role": "assistant", "usage": message_start_usage},
            }
        ),
        _chunk({"type": "content_block_delta", "delta": {"text": "hi"}}),
        _chunk({"type": "content_block_stop"}),
        _chunk(
            {
                "type": "message_delta",
                "delta": {"stop_reason": "end_turn"},
                "usage": (
                    {"output_tokens": 7}
                    if message_delta_usage is None
                    else message_delta_usage
                ),
            }
        ),
        _chunk(
            {
                "type": "message_stop",
                "amazon-bedrock-invocationMetrics": invocation_metrics,
            }
        ),
    ]


class TestClaudeStreamAggregation:
    def test_aggregate_chunks__message_start_reports_cache_tokens__usage_carries_them(
        self,
    ):
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["cacheReadInputTokens"] == 4096
        assert usage["inputTokens"] == 12
        assert usage["outputTokens"] == 7

    def test_aggregate_chunks__no_cache_tokens_reported__usage_reports_zero(self):
        chunks = _stream(
            message_start_usage={"input_tokens": 12, "output_tokens": 0},
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 0
        assert usage["cacheReadInputTokens"] == 0

    def test_aggregate_chunks__invocation_metrics_present__cache_tokens_survive(self):
        # message_stop metrics take precedence for input/output tokens. Cache tokens
        # are only ever reported on message_start, so they must not be reset here.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={"inputTokenCount": 15, "outputTokenCount": 9},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["cacheReadInputTokens"] == 4096
        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9

    def test_aggregate_chunks__message_delta_reports_cache_tokens__delta_wins(self):
        # Anthropic reports running totals on message_delta. When it carries cache
        # counters they supersede the message_start values.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
            message_delta_usage={
                "output_tokens": 7,
                "cache_creation_input_tokens": 2048,
                "cache_read_input_tokens": 8192,
            },
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 2048
        assert usage["cacheReadInputTokens"] == 8192

    def test_aggregate_chunks__metrics_report_cache_tokens__terminal_metrics_win(self):
        # amazon-bedrock-invocationMetrics is the terminal, authoritative record, so it
        # replaces anything message_start or message_delta reported.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={
                "inputTokenCount": 15,
                "outputTokenCount": 9,
                "cacheWriteInputTokenCount": 64,
                "cacheReadInputTokenCount": 128,
            },
            message_delta_usage={
                "output_tokens": 7,
                "cache_creation_input_tokens": 2048,
                "cache_read_input_tokens": 8192,
            },
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 64
        assert usage["cacheReadInputTokens"] == 128
        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9

    def test_aggregate_chunks__usage_built_for_backend__cache_counts_survive(self):
        # Covers the rest of the path the decorator takes for a streamed response:
        # BedrockAggregatedResponse.usage -> build_opik_usage -> BedrockUsage ->
        # to_backend_compatible_full_usage_dict. A regression anywhere in that chain
        # would put the cache counts back to zero on the span.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)
        opik_usage = llm_usage.build_opik_usage(
            provider=opik.LLMProvider.BEDROCK, usage=response.usage
        )
        backend_usage = opik_usage.to_backend_compatible_full_usage_dict()

        assert backend_usage["original_usage.cacheWriteInputTokens"] == 1024
        assert backend_usage["original_usage.cacheReadInputTokens"] == 4096
        assert backend_usage["original_usage.inputTokens"] == 12
        assert backend_usage["original_usage.outputTokens"] == 7

    def test_aggregate_chunks__message_delta_reports_null_cache__earlier_counts_kept(
        self,
    ):
        # A later chunk can report a token field as null. Assigning it through would
        # discard the real message_start count and, because the backend usage dict
        # keeps only integers, drop the field from the span entirely.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
            message_delta_usage={
                "output_tokens": 7,
                "cache_creation_input_tokens": None,
                "cache_read_input_tokens": None,
            },
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)
        backend_usage = llm_usage.build_opik_usage(
            provider=opik.LLMProvider.BEDROCK, usage=response.usage
        ).to_backend_compatible_full_usage_dict()

        assert backend_usage["original_usage.cacheWriteInputTokens"] == 1024
        assert backend_usage["original_usage.cacheReadInputTokens"] == 4096

    def test_aggregate_chunks__metrics_report_null_cache__earlier_counts_kept(self):
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={
                "inputTokenCount": 15,
                "outputTokenCount": 9,
                "cacheWriteInputTokenCount": None,
                "cacheReadInputTokenCount": None,
            },
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)
        backend_usage = llm_usage.build_opik_usage(
            provider=opik.LLMProvider.BEDROCK, usage=response.usage
        ).to_backend_compatible_full_usage_dict()

        assert backend_usage["original_usage.cacheWriteInputTokens"] == 1024
        assert backend_usage["original_usage.cacheReadInputTokens"] == 4096
        assert backend_usage["original_usage.inputTokens"] == 15


def _content_stream(
    events: List[Dict[str, Any]], stop_reason: str
) -> List[Dict[str, Any]]:
    return [
        _chunk(
            {
                "type": "message_start",
                "message": {"role": "assistant", "usage": {"input_tokens": 12}},
            }
        ),
        *[_chunk(event) for event in events],
        _chunk(
            {
                "type": "message_delta",
                "delta": {"stop_reason": stop_reason},
                "usage": {"output_tokens": 7},
            }
        ),
        _chunk({"type": "message_stop"}),
    ]


# Usage of every stream built by _content_stream, in Bedrock format.
_CONTENT_STREAM_USAGE = {
    "inputTokens": 12,
    "outputTokens": 7,
    "cacheWriteInputTokens": 0,
    "cacheReadInputTokens": 0,
    "totalTokens": 19,
}


def _tool_use_events(
    index: int, tool_id: str, tool_name: str, partial_jsons: List[str]
) -> List[Dict[str, Any]]:
    return [
        {
            "type": "content_block_start",
            "index": index,
            "content_block": {
                "type": "tool_use",
                "id": tool_id,
                "name": tool_name,
                "input": {},
            },
        },
        *[
            {
                "type": "content_block_delta",
                "index": index,
                "delta": {"type": "input_json_delta", "partial_json": partial_json},
            }
            for partial_json in partial_jsons
        ],
        {"type": "content_block_stop", "index": index},
    ]


def _text_events(index: int, texts: List[str]) -> List[Dict[str, Any]]:
    return [
        {
            "type": "content_block_start",
            "index": index,
            "content_block": {"type": "text", "text": ""},
        },
        *[
            {
                "type": "content_block_delta",
                "index": index,
                "delta": {"type": "text_delta", "text": text},
            }
            for text in texts
        ],
        {"type": "content_block_stop", "index": index},
    ]


class TestClaudeStreamContentBlocks:
    def test_aggregate_chunks__tool_only_turn__tool_use_block_logged(self):
        tool_id, tool_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        city = random_chars()
        chunks = _content_stream(
            _tool_use_events(0, tool_id, tool_name, ['{"city": ', f'"{city}"}}']),
            stop_reason="tool_use",
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": tool_id,
                    "name": tool_name,
                    "input": {"city": city},
                }
            ],
            "stop_reason": "tool_use",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__thinking_text_and_tool_use__all_blocks_logged_in_order(
        self,
    ):
        tool_id, tool_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        thinking_start, thinking_end = random_chars(), random_chars()
        signature, text = random_chars(), random_chars()
        city = random_chars()
        events = [
            {
                "type": "content_block_start",
                "index": 0,
                "content_block": {"type": "thinking", "thinking": "", "signature": ""},
            },
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "thinking_delta", "thinking": thinking_start},
            },
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "thinking_delta", "thinking": thinking_end},
            },
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "signature_delta", "signature": signature},
            },
            {"type": "content_block_stop", "index": 0},
            *_text_events(1, [text]),
            *_tool_use_events(2, tool_id, tool_name, [f'{{"city": "{city}"}}']),
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(
            _content_stream(events, stop_reason="tool_use")
        )

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {
                    "type": "thinking",
                    "thinking": thinking_start + thinking_end,
                    "signature": signature,
                },
                {"type": "text", "text": text},
                {
                    "type": "tool_use",
                    "id": tool_id,
                    "name": tool_name,
                    "input": {"city": city},
                },
            ],
            "stop_reason": "tool_use",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__tool_call_without_arguments__input_is_empty_object(
        self,
    ):
        tool_id, tool_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        chunks = _content_stream(
            _tool_use_events(0, tool_id, tool_name, [""]), stop_reason="tool_use"
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {"type": "tool_use", "id": tool_id, "name": tool_name, "input": {}}
            ],
            "stop_reason": "tool_use",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__tool_input_cut_off__raw_input_kept(self):
        tool_id, tool_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        cut_off_input = f'{{"city": "{random_chars()}'
        chunks = _content_stream(
            _tool_use_events(0, tool_id, tool_name, [cut_off_input]),
            stop_reason="max_tokens",
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": tool_id,
                    "name": tool_name,
                    "input": cut_off_input,
                }
            ],
            "stop_reason": "max_tokens",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__two_tool_use_blocks_in_one_turn__each_keeps_its_own_input(
        self,
    ):
        first_id, first_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        second_id, second_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        first_city, second_city = random_chars(), random_chars()
        events = [
            # Interleaved deltas, as the stream can deliver them.
            *_tool_use_events(0, first_id, first_name, [])[:1],
            *_tool_use_events(1, second_id, second_name, [])[:1],
            {
                "type": "content_block_delta",
                "index": 1,
                "delta": {
                    "type": "input_json_delta",
                    "partial_json": f'{{"city": "{second_city}"}}',
                },
            },
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {
                    "type": "input_json_delta",
                    "partial_json": f'{{"city": "{first_city}"}}',
                },
            },
            {"type": "content_block_stop", "index": 0},
            {"type": "content_block_stop", "index": 1},
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(
            _content_stream(events, stop_reason="tool_use")
        )

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": first_id,
                    "name": first_name,
                    "input": {"city": first_city},
                },
                {
                    "type": "tool_use",
                    "id": second_id,
                    "name": second_name,
                    "input": {"city": second_city},
                },
            ],
            "stop_reason": "tool_use",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__redacted_thinking_block__passed_through_as_is(self):
        data = random_chars(24)
        text = random_chars()
        # A redacted_thinking block only has a start event, no deltas.
        events = [
            {
                "type": "content_block_start",
                "index": 0,
                "content_block": {"type": "redacted_thinking", "data": data},
            },
            {"type": "content_block_stop", "index": 0},
            *_text_events(1, [text]),
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(
            _content_stream(events, stop_reason="end_turn")
        )

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {"type": "redacted_thinking", "data": data},
                {"type": "text", "text": text},
            ],
            "stop_reason": "end_turn",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__delta_without_content_block_start__logged_as_text(
        self,
    ):
        first, second = random_chars(), random_chars()
        events = [
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "text_delta", "text": first},
            },
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "text_delta", "text": second},
            },
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(
            _content_stream(events, stop_reason="end_turn")
        )

        assert response.native_response == {
            "role": "assistant",
            "content": [{"type": "text", "text": first + second}],
            "stop_reason": "end_turn",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__empty_text_block_before_tool_use__empty_block_dropped(
        self,
    ):
        tool_id, tool_name = f"toolu_{random_chars()}", f"tool_{random_chars()}"
        city = random_chars()
        events = [
            *_text_events(0, []),
            *_tool_use_events(1, tool_id, tool_name, [f'{{"city": "{city}"}}']),
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(
            _content_stream(events, stop_reason="tool_use")
        )

        assert response.native_response == {
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": tool_id,
                    "name": tool_name,
                    "input": {"city": city},
                }
            ],
            "stop_reason": "tool_use",
        }
        assert response.usage == _CONTENT_STREAM_USAGE

    def test_aggregate_chunks__text_with_citations__citations_logged(self):
        text = random_chars()
        citation = {
            "type": "char_location",
            "cited_text": text,
            "document_index": 0,
            "start_char_index": 0,
            "end_char_index": len(text),
        }
        events = [
            *_text_events(0, [text])[:-1],
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "citations_delta", "citation": citation},
            },
            {"type": "content_block_stop", "index": 0},
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(
            _content_stream(events, stop_reason="end_turn")
        )

        assert response.native_response == {
            "role": "assistant",
            "content": [{"type": "text", "text": text, "citations": [citation]}],
            "stop_reason": "end_turn",
        }
        assert response.usage == _CONTENT_STREAM_USAGE
