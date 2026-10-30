import litellm
from litellm.types.utils import (
    ChatCompletionMessageToolCall,
    Choices,
    Function,
    Message,
    ModelResponse,
)

import opik
from opik.integrations.litellm import track_completion


def test_track_completion__streamed_tool_call__span_output_has_the_tool_call(
    fake_backend,
):
    arguments = '{"city": "Paris"}'
    mock_response = ModelResponse(
        choices=[
            Choices(
                index=0,
                finish_reason="tool_calls",
                message=Message(
                    role="assistant",
                    content=None,
                    tool_calls=[
                        ChatCompletionMessageToolCall(
                            id="call_1",
                            type="function",
                            function=Function(name="get_weather", arguments=arguments),
                        )
                    ],
                ),
            )
        ]
    )
    messages = [{"role": "user", "content": "Weather in Paris?"}]

    stream = track_completion()(litellm.completion)(
        model="gpt-4o-mini",
        messages=messages,
        stream=True,
        mock_response=mock_response,
    )
    list(stream)
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    message = span.output["choices"][0]["message"]
    assert message["tool_calls"] == [
        {
            "id": "call_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": arguments},
        }
    ]
    assert span.output["choices"][0]["finish_reason"] == "tool_calls"
