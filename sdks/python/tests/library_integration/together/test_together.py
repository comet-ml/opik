import asyncio

import pytest
import together
from together.types.chat.chat_completion import (
    ChatCompletion,
    ChatCompletionUsage,
    Choice,
    ChoiceMessage,
)
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

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = "meta-llama/Llama-3.3-70B-Instruct-Turbo"


def _mock_completion(
    content: str = "Blue, due to Rayleigh scattering.",
) -> ChatCompletion:
    return ChatCompletion(
        id="t1",
        object="chat.completion",
        created=0,
        model=MODEL,
        prompt=[],
        choices=[
            Choice(
                index=0,
                finish_reason="stop",
                message=ChoiceMessage(role="assistant", content=content),
            )
        ],
        usage=ChatCompletionUsage(
            prompt_tokens=10, completion_tokens=8, total_tokens=18
        ),
    )


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
def test_together_chat_completions_create__happyflow(
    fake_backend, monkeypatch, project_name, expected_project_name
):
    client = together.Together(api_key="fake-api-key")
    monkeypatch.setattr(
        client.chat.completions, "_post", lambda *args, **kwargs: _mock_completion()
    )
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


def test_together_chat_completions_create__async__happyflow(fake_backend, monkeypatch):
    client = together.AsyncTogether(api_key="fake-api-key")

    async def _mock_post(*args, **kwargs):
        return _mock_completion()

    monkeypatch.setattr(client.chat.completions, "_post", _mock_post)
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


def test_together_chat_completions_create__error__span_has_error_info(
    fake_backend, monkeypatch
):
    client = together.Together(api_key="fake-api-key")

    def _raise(*args, **kwargs):
        raise ValueError("boom")

    monkeypatch.setattr(client.chat.completions, "_post", _raise)
    tracked = track_together(client)

    with pytest.raises(ValueError):
        tracked.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": "hi"}]
        )
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].error_info is not None


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
