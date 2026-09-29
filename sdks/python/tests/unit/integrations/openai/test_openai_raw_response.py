import httpx
import openai
import pytest

import opik
from opik.integrations.openai import track_openai

MODEL = "gpt-4o-mini"

COMPLETION_PAYLOAD = {
    "id": "chatcmpl-1",
    "object": "chat.completion",
    "created": 1,
    "model": MODEL,
    "choices": [
        {
            "index": 0,
            "finish_reason": "stop",
            "message": {"role": "assistant", "content": "Some response"},
        }
    ],
    "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7},
}


@pytest.fixture
def openai_client():
    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, json=COMPLETION_PAYLOAD)
    )
    return openai.OpenAI(
        api_key="fake-api-key", http_client=httpx.Client(transport=transport)
    )


def test_openai_client_chat_completions_create__called_via_with_raw_response__completion_content_and_usage_logged(
    fake_backend, openai_client
):
    """CrewAI and LiteLLM call `with_raw_response.create()` to read response headers,
    which returns the unparsed HTTP response instead of a ChatCompletion."""
    wrapped_client = track_openai(openai_client)

    raw_response = wrapped_client.chat.completions.with_raw_response.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Tell a fact"}],
    )

    assert raw_response.parse().choices[0].message.content == "Some response"

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]

    assert llm_span.output["choices"][0]["message"]["content"] == "Some response"
    assert llm_span.model == MODEL
    assert llm_span.provider == "openai"
    assert llm_span.usage["prompt_tokens"] == 5
    assert llm_span.usage["completion_tokens"] == 2
    assert llm_span.usage["total_tokens"] == 7
