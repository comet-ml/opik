from typing import Any, Dict, List

from opik.integrations.bedrock.converse import chunks_aggregator


def _delta(delta: Dict[str, Any], index: int = 0) -> Dict[str, Any]:
    return {"contentBlockDelta": {"delta": delta, "contentBlockIndex": index}}


def _tool_use_block(
    tool_use_id: str, input_fragments: List[str], index: int = 0
) -> List[Dict[str, Any]]:
    return [
        {
            "contentBlockStart": {
                "start": {"toolUse": {"toolUseId": tool_use_id, "name": "get_weather"}},
                "contentBlockIndex": index,
            }
        },
        *[_delta({"toolUse": {"input": f}}, index) for f in input_fragments],
        {"contentBlockStop": {"contentBlockIndex": index}},
    ]


class TestConverseStreamAggregation:
    def test_aggregate__tool_use_stream__tool_input_fragments_joined(self):
        # Events recorded from us.openai.gpt-6-sol converse_stream with a tool.
        events = [
            {"messageStart": {"role": "assistant"}},
            *_tool_use_block("call_1", ['{"', "city", '":"', "Paris", '"}']),
            {"messageStop": {"stopReason": "tool_use"}},
            {"metadata": {"usage": {"inputTokens": 46, "outputTokens": 18}}},
        ]

        result = chunks_aggregator.aggregate_converse_stream_chunks(events)

        content = result["output"]["message"]["content"][0]
        assert content["toolUse"] == {
            "toolUseId": "call_1",
            "name": "get_weather",
            "input": '{"city":"Paris"}',
        }
        assert result["stopReason"] == "tool_use"
        assert result["usage"] == {"inputTokens": 46, "outputTokens": 18}

    def test_aggregate__parallel_tool_use_stream__one_entry_per_tool_call(self):
        # Two tool calls recorded from one us.openai.gpt-6-sol turn (blocks 0 and 1).
        events = [
            {"messageStart": {"role": "assistant"}},
            *_tool_use_block("call_1", ["{", '"city', '":"', "Paris", '"}'], index=0),
            *_tool_use_block("call_2", ["{", '"city', '":"', "Tokyo", '"}'], index=1),
            {"messageStop": {"stopReason": "tool_use"}},
        ]

        result = chunks_aggregator.aggregate_converse_stream_chunks(events)

        assert result["output"]["message"]["content"] == [
            {
                "text": "",
                "toolUse": {
                    "toolUseId": "call_1",
                    "name": "get_weather",
                    "input": '{"city":"Paris"}',
                },
            },
            {
                "toolUse": {
                    "toolUseId": "call_2",
                    "name": "get_weather",
                    "input": '{"city":"Tokyo"}',
                },
            },
        ]

    def test_aggregate__text_stream__text_joined(self):
        events = [
            {"messageStart": {"role": "assistant"}},
            _delta({"text": "po"}),
            _delta({"text": "ng"}),
            {"messageStop": {"stopReason": "end_turn"}},
        ]

        result = chunks_aggregator.aggregate_converse_stream_chunks(events)

        assert result["output"]["message"]["content"] == [{"text": "pong"}]
        assert result["stopReason"] == "end_turn"
