from typing import Any, Dict, List, Optional

import pytest

from opik.integrations.bedrock.converse import chunks_aggregator

# Tool input fragments as us.openai.gpt-6-sol streams them.
PARIS = ['{"', "city", '":"', "Paris", '"}']
TOKYO = ['{"', "city", '":"', "Tokyo", '"}']


def _block(
    deltas: List[Dict[str, Any]],
    index: Optional[int],
    start: Optional[Dict[str, Any]] = None,
) -> List[Dict[str, Any]]:
    at = {} if index is None else {"contentBlockIndex": index}
    events = [{"contentBlockStart": {"start": start, **at}}] if start else []
    events += [{"contentBlockDelta": {"delta": delta, **at}} for delta in deltas]
    return events + [{"contentBlockStop": at}]


def _call(
    tool_use_id: str, fragments: List[str], index: Optional[int] = 0
) -> List[Dict[str, Any]]:
    start = {"toolUse": {"toolUseId": tool_use_id, "name": "get_weather"}}
    return _block([{"toolUse": {"input": f}} for f in fragments], index, start)


def _tool_use(tool_use_id: str, tool_input: Any) -> Dict[str, Any]:
    return {
        "toolUse": {
            "toolUseId": tool_use_id,
            "name": "get_weather",
            "input": tool_input,
        }
    }


PARIS_CALL = _tool_use("call_1", {"city": "Paris"})
TOKYO_CALL = _tool_use("call_2", {"city": "Tokyo"})


@pytest.mark.parametrize(
    "events, expected_content",
    [
        (_call("call_1", PARIS), [PARIS_CALL]),
        (
            _call("call_1", PARIS) + _call("call_2", TOKYO, index=1),
            [PARIS_CALL, TOKYO_CALL],
        ),
        (
            _block([{"text": "Let me "}, {"text": "check."}], 0)
            + _call("call_1", PARIS, index=1),
            [{"text": "Let me check."}, PARIS_CALL],
        ),
        (
            _call("call_2", TOKYO, index=1) + _call("call_1", PARIS),
            [PARIS_CALL, TOKYO_CALL],
        ),
        (
            _call("call_1", PARIS, index=None) + _call("call_2", TOKYO, index=None),
            [PARIS_CALL, TOKYO_CALL],
        ),
        (_call("call_1", [""]), [_tool_use("call_1", {})]),
        (_call("call_1", ['{"city":"Pa']), [_tool_use("call_1", '{"city":"Pa')]),
        (_block([{"text": "po"}, {"text": "ng"}], 0), [{"text": "pong"}]),
    ],
    ids=[
        "one-call",
        "parallel-calls",
        "text-then-call",
        "blocks-out-of-order",
        "no-content-block-index",
        "no-arguments",
        "cut-off-input",
        "text-only",
    ],
)
def test_aggregate_converse_stream_chunks__content_blocks__converse_layout(
    events, expected_content
):
    result = chunks_aggregator.aggregate_converse_stream_chunks(
        [{"messageStart": {"role": "assistant"}}, *events]
    )

    assert result["output"]["message"]["content"] == expected_content
