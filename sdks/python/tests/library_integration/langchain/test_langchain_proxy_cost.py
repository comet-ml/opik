"""Tests for provider override and provider-reported cost passthrough.

These cover the LiteLLM-proxy scenario (OPIK-7180): when LangChain's ChatOpenAI
points at an OpenAI-compatible proxy, the provider is auto-detected as the proxy
hostname (so the backend can't price the call) and the proxy-reported cost from
the ``x-litellm-response-cost`` response header is never captured.

The end-to-end tests drive the real ``langchain_openai`` + ``openai`` stack
through a mocked HTTP transport that mimics a LiteLLM proxy — real request/
response parsing, but no network and no spend. The response headers below were
captured from a real local LiteLLM proxy; LangChain only surfaces them (under
``response_metadata["headers"]``) when ``include_response_headers=True`` is set.
"""

import httpx
import pytest
from langchain_openai import ChatOpenAI

from opik import LLMProvider
from opik.integrations.langchain import OpikTracer
from opik.integrations.langchain import response_cost_extractors
from ...testlib import ANY_BUT_NONE, ANY_DICT, SpanModel, TraceModel, assert_equal

PROXY_MODEL = "eu.anthropic.claude-haiku-4-5"

# Headers a LiteLLM proxy attaches to its response; x-litellm-response-cost
# carries the pre-computed request cost (a string, in scientific notation).
LITELLM_PROXY_HEADERS = {
    "content-type": "application/json",
    "x-litellm-call-id": "2a8d6603-6471-4eee-a0c4-5b9724b70d08",
    "x-litellm-model-id": (
        "b93a836b35cc4858c11e17345818a56ef9e5a51744a774694f2453140f7afdee"
    ),
    "x-litellm-version": "1.83.14",
    "x-litellm-response-cost": "3.5e-05",
}


def _proxy_response(request: httpx.Request) -> httpx.Response:
    body = {
        "id": "chatcmpl-mock",
        "object": "chat.completion",
        "created": 1,
        "model": PROXY_MODEL,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Paris is the capital of France.",
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
    }
    return httpx.Response(200, json=body, headers=LITELLM_PROXY_HEADERS)


def _proxy_chat_model() -> ChatOpenAI:
    return ChatOpenAI(
        model="mock-model",
        base_url="http://litellm.local:4000",
        api_key="sk-anything",
        include_response_headers=True,  # required for the cost header to surface
        http_client=httpx.Client(transport=httpx.MockTransport(_proxy_response)),
    )


def test_langchain__proxy_cost_in_response_metadata__recorded_on_span(fake_backend):
    callback = OpikTracer()

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="ChatOpenAI",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="ChatOpenAI",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=ANY_BUT_NONE,
                model=ANY_BUT_NONE,
                provider=ANY_BUT_NONE,
                total_cost=3.5e-05,
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_langchain__provider_override_with_string__recorded_on_span(fake_backend):
    callback = OpikTracer(provider="bedrock")

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider == "bedrock"
    assert llm_span.total_cost == 3.5e-05


def test_langchain__provider_override_with_enum__normalized_to_string(fake_backend):
    callback = OpikTracer(provider=LLMProvider.BEDROCK)

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider == "bedrock"
    assert isinstance(llm_span.provider, str)


def test_langchain__provider_override_with_callable__resolved_per_run(fake_backend):
    def resolve_provider(context):
        return "bedrock" if "anthropic" in (context.model or "") else None

    callback = OpikTracer(provider=resolve_provider)

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider == "bedrock"


def test_langchain__provider_resolver_raises__falls_back_to_autodetection(fake_backend):
    def resolve_provider(context):
        raise ValueError("boom")

    callback = OpikTracer(provider=resolve_provider)

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    # The failing callback must not break trace logging; the span is still logged
    # with the auto-detected provider and the proxy cost.
    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider is not None
    assert llm_span.total_cost == 3.5e-05


@pytest.mark.parametrize(
    "response_metadata, expected_cost",
    [
        # Real shape: cost nested under "headers" (include_response_headers=True).
        ({"headers": {"x-litellm-response-cost": "3.5e-05"}}, 3.5e-05),
        # Fallback: header flattened onto response_metadata.
        ({"x-litellm-response-cost": "0.00042"}, 0.00042),
        ({"x-litellm-response-cost": 0.0015}, 0.0015),
        ({"finish_reason": "stop"}, None),
        ({"headers": {"content-type": "application/json"}}, None),
        ({"headers": {"x-litellm-response-cost": "not-a-number"}}, None),
        ({}, None),
    ],
)
def test_try_extract_response_cost__response_metadata_variants__returns_expected_cost(
    response_metadata, expected_cost
):
    run_dict = {
        "outputs": {
            "generations": [
                [{"message": {"kwargs": {"response_metadata": response_metadata}}}]
            ]
        }
    }

    assert response_cost_extractors.try_extract_response_cost(run_dict) == expected_cost


@pytest.mark.parametrize(
    "run_dict",
    [
        {},
        {"outputs": {}},
        {"outputs": {"generations": []}},
        {"outputs": {"generations": [[{"message": {"kwargs": {}}}]]}},
    ],
)
def test_try_extract_response_cost__malformed_run_dict__returns_none(run_dict):
    assert response_cost_extractors.try_extract_response_cost(run_dict) is None
