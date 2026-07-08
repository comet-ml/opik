import asyncio

import groq
import pytest
from groq.types.chat.chat_completion import ChatCompletion, Choice
from groq.types.chat.chat_completion_message import ChatCompletionMessage
from groq.types.completion_usage import CompletionUsage

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.groq import track_groq

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = "llama-3.3-70b-versatile"


def _mock_completion(
    content: str = "Blue, due to Rayleigh scattering.",
) -> ChatCompletion:
    return ChatCompletion(
        id="cmpl-1",
        object="chat.completion",
        created=0,
        model=MODEL,
        choices=[
            Choice(
                index=0,
                finish_reason="stop",
                logprobs=None,
                message=ChatCompletionMessage(role="assistant", content=content),
            )
        ],
        usage=CompletionUsage(prompt_tokens=10, completion_tokens=8, total_tokens=18),
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("groq-integration-test", "groq-integration-test"),
    ],
)
def test_groq_chat_completions_create__happyflow(
    fake_backend, monkeypatch, project_name, expected_project_name
):
    client = groq.Groq(api_key="fake-api-key")
    wrapped_client = track_groq(client, project_name=project_name)
    monkeypatch.setattr(
        client.chat.completions, "_post", lambda *args, **kwargs: _mock_completion()
    )

    messages = [{"role": "user", "content": "Why is the sky blue?"}]
    response = wrapped_client.chat.completions.create(
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
        tags=["groq"],
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
                tags=["groq"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                type="llm",
                usage=ANY_DICT,
                model=MODEL,
                provider="groq",
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_groq_chat_completions_create__async__happyflow(fake_backend, monkeypatch):
    client = groq.AsyncGroq(api_key="fake-api-key")
    wrapped_client = track_groq(client)

    async def _mock_post(*args, **kwargs):
        return _mock_completion()

    monkeypatch.setattr(client.chat.completions, "_post", _mock_post)

    messages = [{"role": "user", "content": "Why is the sky blue?"}]

    async def run() -> None:
        await wrapped_client.chat.completions.create(model=MODEL, messages=messages)

    asyncio.run(run())
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.provider == "groq"
    assert span.type == "llm"
    assert span.model == MODEL


def test_groq_chat_completions_create__error__span_and_trace_finished_gracefully(
    fake_backend, monkeypatch
):
    client = groq.Groq(api_key="fake-api-key")
    wrapped_client = track_groq(client)

    def _raise(*args, **kwargs):
        raise Exception("network is down")

    monkeypatch.setattr(client.chat.completions, "_post", _raise)

    with pytest.raises(Exception):
        wrapped_client.chat.completions.create(model=MODEL, messages=None)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.spans[0].error_info is not None
    assert trace_tree.spans[0].provider == "groq"
