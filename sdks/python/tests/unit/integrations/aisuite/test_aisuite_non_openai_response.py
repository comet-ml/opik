import aisuite
import pytest
from aisuite.framework import ChatCompletionResponse
from aisuite.framework.message import (
    ChatCompletionMessageToolCall,
    CompletionUsage,
    Function,
    Message,
)

import opik
from opik.integrations.aisuite import track_aisuite

from .... import llm_constants


class _FakeProvider:
    """Returns what aisuite's non-OpenAI providers build for a tool-use reply."""

    def __init__(self, usage):
        self._usage = usage

    def chat_completions_create(self, model, messages, **kwargs):
        response = ChatCompletionResponse()
        response.choices[0].finish_reason = "tool_calls"
        response.choices[0].message = Message(
            role="assistant",
            content=None,
            tool_calls=[
                ChatCompletionMessageToolCall(
                    id="toolu_1",
                    type="function",
                    function=Function(
                        name="get_weather", arguments='{"city": "Paris"}'
                    ),
                )
            ],
        )
        response.usage = self._usage
        return response


@pytest.mark.parametrize(
    "usage",
    [
        CompletionUsage(prompt_tokens=12, completion_tokens=5, total_tokens=17),
        # aisuite's Cohere provider sets usage as a plain dict
        {"prompt_tokens": 12, "completion_tokens": 5, "total_tokens": 17},
    ],
    ids=["completion_usage", "dict"],
)
def test_aisuite_non_openai_provider__tool_calls_and_usage_logged(fake_backend, usage):
    client = aisuite.Client()
    client.providers["anthropic"] = _FakeProvider(usage)
    wrapped_client = track_aisuite(client)

    wrapped_client.chat.completions.create(
        model=llm_constants.AISUITE_ANTHROPIC_CLAUDE_HAIKU,
        messages=[{"role": "user", "content": "What's the weather in Paris?"}],
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]

    choice = llm_span.output["choices"][0]
    assert choice["message"]["tool_calls"] == [
        {
            "id": "toolu_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": '{"city": "Paris"}'},
        }
    ]
    assert choice["finish_reason"] == "tool_calls"
    assert llm_span.provider == "anthropic"
    assert llm_span.usage["prompt_tokens"] == 12
    assert llm_span.usage["completion_tokens"] == 5
    assert llm_span.usage["total_tokens"] == 17
