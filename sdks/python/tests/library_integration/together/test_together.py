import asyncio
import json

import httpx
import pytest
import respx
import together
from together.types.chat.chat_completion_chunk import (
    ChatCompletionChunk,
    Choice as ChunkChoice,
    ChoiceDelta,
)

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.together import (
    chat_completion_chunks_aggregator,
    opik_tracker,
    stream_patchers,
    track_together,
)

from ... import llm_constants
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = llm_constants.TOGETHER_LLAMA


# These tests never reach Together: there is no API key in CI. The response is
# mocked at the HTTP layer rather than by stubbing ``_post`` so the library's own
# request building and response parsing still run — that is the only part of the
# provider contract a keyless suite can actually regress on. Stubbing ``_post``
# would hand the integration a ``ChatCompletion`` we built ourselves and would
# keep passing even if Together changed how it parses a response.
# Matched on path only: together<3 sends chat to api.together.ai, together>=3 to
# api-inference.together.ai.
COMPLETIONS_ROUTE = "/v1/chat/completions"

CONTENT = "Blue, due to Rayleigh scattering."


def _completion_body(content: str = CONTENT) -> dict:
    """The JSON Together returns for a non-streaming chat completion."""
    return {
        "id": "t1",
        "object": "chat.completion",
        "created": 0,
        "model": MODEL,
        "prompt": [],
        "choices": [
            {
                "index": 0,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": content},
            }
        ],
        "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18},
    }


def _sse(*chunks: dict) -> bytes:
    """Encode chunks as the ``text/event-stream`` body Together sends."""
    body = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks)
    return (body + "data: [DONE]\n\n").encode()


def _stream_chunk(content=None, finish_reason=None, usage=None) -> dict:
    delta = {} if content is None else {"content": content}
    chunk = {
        "id": "t1",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": MODEL,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
    }
    if usage is not None:
        chunk["usage"] = usage
    return chunk


def _chunk(
    content=None,
    reasoning=None,
    reasoning_content=None,
    role="assistant",
    finish_reason=None,
):
    # ChoiceDelta.role is a required literal, so only set the fields under test.
    delta_kwargs = {
        k: v
        for k, v in {
            "role": role,
            "content": content,
            "reasoning": reasoning,
            "reasoning_content": reasoning_content,
        }.items()
        if v is not None
    }
    return ChatCompletionChunk(
        id="t1",
        object="chat.completion.chunk",
        created=0,
        model=MODEL,
        system_fingerprint="fp_test",
        choices=[
            ChunkChoice(
                index=0,
                finish_reason=finish_reason,
                delta=ChoiceDelta(**delta_kwargs),
            )
        ],
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("together-integration-test", "together-integration-test"),
    ],
)
@respx.mock()
def test_together_chat_completions_create__happyflow(
    respx_mock, fake_backend, project_name, expected_project_name
):
    respx_mock.post(path=COMPLETIONS_ROUTE).mock(
        return_value=httpx.Response(200, json=_completion_body())
    )
    client = together.Together(api_key="fake-api-key")
    tracked = track_together(client, project_name=project_name)

    messages = [{"role": "user", "content": "Why is the sky blue?"}]
    response = tracked.chat.completions.create(
        model=MODEL,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert response.choices[0].message.content == "Blue, due to Rayleigh scattering."

    expected_output = {"choices": response.model_dump(mode="json")["choices"]}

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input={"messages": messages},
        output=expected_output,
        tags=["together"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completion_create",
                input={"messages": messages},
                output=expected_output,
                tags=["together"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                type="llm",
                usage=ANY_DICT,
                model=MODEL,
                provider="together",
                spans=[],
                source="sdk",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@respx.mock()
def test_together_chat_completions_create__async__happyflow(respx_mock, fake_backend):
    respx_mock.post(path=COMPLETIONS_ROUTE).mock(
        return_value=httpx.Response(200, json=_completion_body())
    )
    client = together.AsyncTogether(api_key="fake-api-key")
    tracked = track_together(client)

    asyncio.run(
        tracked.chat.completions.create(
            model=MODEL,
            messages=[{"role": "user", "content": "Why is the sky blue?"}],
        )
    )
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].provider == "together"


@respx.mock()
def test_together_chat_completions_create__error__span_has_error_info(
    respx_mock, fake_backend
):
    # A 500 from Together, not a synthetic exception: this exercises the
    # library's own error mapping on the way to the span.
    respx_mock.post(path=COMPLETIONS_ROUTE).mock(
        return_value=httpx.Response(500, json={"error": "boom"})
    )
    client = together.Together(api_key="fake-api-key")
    tracked = track_together(client)

    with pytest.raises(together.APIStatusError):
        tracked.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": "hi"}]
        )
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].error_info is not None


@respx.mock()
def test_together_chat_completions_create__stream__happyflow(respx_mock, fake_backend):
    """Drive a stream the way a user does, not through the aggregator directly.

    The aggregator tests below call ``aggregate()`` on hand-built chunks. This one
    goes through ``create(stream=True)`` and the real SSE parser, so it also covers
    the stream patching and the span that gets ended when iteration finishes.
    """
    respx_mock.post(path=COMPLETIONS_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(
                _stream_chunk("Blue"),
                _stream_chunk(", due to Rayleigh"),
                _stream_chunk(
                    " scattering.",
                    finish_reason="stop",
                    usage={
                        "prompt_tokens": 10,
                        "completion_tokens": 8,
                        "total_tokens": 18,
                    },
                ),
            ),
        )
    )
    client = together.Together(api_key="fake-api-key")
    tracked = track_together(client)

    stream = tracked.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Why is the sky blue?"}],
        stream=True,
    )
    received = "".join(
        event.choices[0].delta.content
        for event in stream
        if event.choices and event.choices[0].delta.content
    )
    opik.flush_tracker()

    assert received == CONTENT

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.provider == "together"
    assert span.model == MODEL
    # The span output is the aggregated stream, not the individual chunks.
    assert span.output["choices"][0]["message"]["content"] == CONTENT
    assert span.usage is not None


@respx.mock(base_url=BASE_URL)
def test_together_chat_completions_create__stream_with_tool_call__tool_call_in_span_output(
    respx_mock, fake_backend
):
    """A streamed tool call goes through the real SSE parser and into the span output."""
    tool_call_chunk = {
        "id": "t1",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": MODEL,
        "choices": [
            {
                "index": 0,
                "delta": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "call_1",
                            "type": "function",
                            "function": {
                                "name": "get_weather",
                                "arguments": '{"city": "Paris"}',
                            },
                        }
                    ],
                },
                "finish_reason": None,
            }
        ],
    }
    respx_mock.post(COMPLETIONS_ROUTE).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=_sse(tool_call_chunk, _stream_chunk(finish_reason="tool_calls")),
        )
    )
    client = together.Together(api_key="fake-api-key")
    tracked = track_together(client)

    stream = tracked.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Weather in Paris?"}],
        stream=True,
    )
    list(stream)
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    choice = fake_backend.trace_trees[0].spans[0].output["choices"][0]
    assert choice["finish_reason"] == "tool_calls"
    assert choice["message"]["tool_calls"] == [
        {
            "id": "call_1",
            "type": "function",
            "function": {"name": "get_weather", "arguments": '{"city": "Paris"}'},
        }
    ]


def test_aggregate__reasoning_deltas__kept_alongside_content():
    """Together streams reasoning in its own delta field, unlike OpenAI."""
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _chunk(role="assistant"),
            _chunk(reasoning="The sky scatters "),
            _chunk(reasoning="short wavelengths."),
            _chunk(content="Blue."),
            _chunk(finish_reason="stop"),
        ]
    )

    message = aggregated.choices[0]["message"]
    assert message["content"] == "Blue."
    assert message["reasoning"] == "The sky scatters short wavelengths."


def test_aggregate__reasoning_content_field__also_captured():
    """Some Together models populate `reasoning_content` rather than `reasoning`."""
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _chunk(role="assistant"),
            _chunk(reasoning_content="Step one. "),
            _chunk(content="Done."),
            _chunk(finish_reason="stop"),
        ]
    )

    assert aggregated.choices[0]["message"]["reasoning"] == "Step one. "


def test_aggregate__no_reasoning_deltas__reasoning_key_absent():
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _chunk(role="assistant"),
            _chunk(content="Blue."),
            _chunk(finish_reason="stop"),
        ]
    )

    assert "reasoning" not in aggregated.choices[0]["message"]


def test_aggregate__streamed_tool_calls__kept_in_message():
    """Together sends each tool call whole, in one delta, with its index."""
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _chunk(role="assistant"),
            ChatCompletionChunk.model_validate(
                {
                    "id": "t1",
                    "object": "chat.completion.chunk",
                    "created": 0,
                    "model": MODEL,
                    "choices": [
                        {
                            "index": 0,
                            "delta": {
                                "role": "assistant",
                                "tool_calls": [
                                    {
                                        "index": 0,
                                        "id": "call_1",
                                        "type": "function",
                                        "function": {
                                            "name": "get_weather",
                                            "arguments": '{"city": "Paris"}',
                                        },
                                    }
                                ],
                            },
                        }
                    ],
                }
            ),
            _chunk(finish_reason="tool_calls"),
        ]
    )

    assert aggregated.choices[0] == {
        "index": 0,
        "finish_reason": "tool_calls",
        "message": {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "id": "call_1",
                    "type": "function",
                    "function": {
                        "name": "get_weather",
                        "arguments": '{"city": "Paris"}',
                    },
                }
            ],
        },
    }


def test_aggregate__no_tool_call_deltas__tool_calls_key_absent():
    aggregated = chat_completion_chunks_aggregator.aggregate(
        [
            _chunk(role="assistant"),
            _chunk(content="Blue."),
            _chunk(finish_reason="stop"),
        ]
    )

    assert "tool_calls" not in aggregated.choices[0]["message"]


def test_extract_metadata_from_client__credentials_in_base_url__stripped():
    """base_url reaches span metadata, so userinfo and query must not ride along."""
    client = together.Together(
        api_key="fake-api-key",
        base_url="https://user:secret@proxy.internal:8443/openai/v1?token=abc#frag",
    )

    metadata = opik_tracker._extract_metadata_from_client(client)

    assert metadata == {"base_url": "https://proxy.internal:8443/openai/v1"}
    assert "secret" not in metadata["base_url"]
    assert "abc" not in metadata["base_url"]


def test_patch_sync_stream__two_streams_patched_before_iteration__each_uses_its_own_callback():
    """The patch is installed on the class, so per-call state must live on the instance."""
    finalized = []

    def make_callback(tag):
        def callback(
            output,
            error_info,
            capture_output,
            generators_span_to_end,
            generators_trace_to_end,
        ):
            finalized.append((tag, generators_span_to_end, output))

        return callback

    class FakeStream(together.Stream):
        def __init__(self, items):
            self._items = items

    original = stream_patchers.original_stream_iter_method
    stream_patchers.original_stream_iter_method = lambda self: iter(self._items)
    try:
        first = FakeStream(["a1", "a2"])
        second = FakeStream(["b1", "b2"])
        stream_patchers.patch_sync_stream(
            first, "SPAN_A", None, list, make_callback("A")
        )
        stream_patchers.patch_sync_stream(
            second, "SPAN_B", None, list, make_callback("B")
        )

        assert list(first) == ["a1", "a2"]
        assert list(second) == ["b1", "b2"]
    finally:
        stream_patchers.original_stream_iter_method = original

    assert finalized == [
        ("A", "SPAN_A", ["a1", "a2"]),
        ("B", "SPAN_B", ["b1", "b2"]),
    ]
