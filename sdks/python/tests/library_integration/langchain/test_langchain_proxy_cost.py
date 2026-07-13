"""Tests for provider override and provider-reported cost passthrough.

These cover the LiteLLM-proxy scenario (OPIK-7180): when LangChain's ChatOpenAI
points at an OpenAI-compatible proxy, the provider is auto-detected as the proxy
hostname (so the backend can't price the call) and the proxy-reported cost from
the ``x-litellm-response-cost`` response header is never captured.

They run fully offline: a fake chat model carries the same ``response_metadata``
a real LiteLLM proxy surfaces through LangChain.

The nested ``headers`` shape below was captured from a real LiteLLM proxy driving
``langchain_openai.ChatOpenAI(include_response_headers=True)`` — LangChain only
surfaces response headers (where the cost lives) when that flag is set.
"""

import pytest
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage

from opik import LLMProvider
from opik.integrations.langchain import OpikTracer
from opik.integrations.langchain import response_cost_extractors
from ...testlib import ANY_BUT_NONE, ANY_DICT, SpanModel, TraceModel, assert_equal

# Faithful to the shape a LiteLLM proxy surfaces in LangChain's response_metadata
# for an AWS Bedrock model. The cost header is nested under "headers" (verified
# against a real litellm proxy); note it is a string in scientific notation.
LITELLM_PROXY_RESPONSE_METADATA = {
    "model_provider": "openai",
    "model_name": "eu.anthropic.claude-haiku-4-5",
    "finish_reason": "stop",
    "headers": {
        "x-litellm-call-id": "2a8d6603-6471-4eee-a0c4-5b9724b70d08",
        "x-litellm-model-id": "b93a836b35cc4858c11e17345818a56ef9e5a51744a774694f2453140f7afdee",
        "x-litellm-response-cost": "3.5e-05",
    },
}


def _proxy_chat_model() -> GenericFakeChatModel:
    message = AIMessage(
        content="Paris is the capital of France.",
        response_metadata=dict(LITELLM_PROXY_RESPONSE_METADATA),
    )
    return GenericFakeChatModel(messages=iter([message]))


def test_langchain__proxy_cost_is_captured_from_response_metadata(fake_backend):
    callback = OpikTracer()

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="GenericFakeChatModel",
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
                name="GenericFakeChatModel",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                total_cost=3.5e-05,
                spans=[],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_langchain__provider_override_with_string(fake_backend):
    callback = OpikTracer(provider="bedrock")

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider == "bedrock"
    assert llm_span.total_cost == 3.5e-05


def test_langchain__provider_override_with_enum_is_normalized_to_string(fake_backend):
    callback = OpikTracer(provider=LLMProvider.BEDROCK)

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider == "bedrock"
    assert isinstance(llm_span.provider, str)


def test_langchain__provider_override_with_callable_resolves_per_run(fake_backend):
    def resolve_provider(run_dict):
        model = run_dict["outputs"]["generations"][-1][-1]["message"]["kwargs"][
            "response_metadata"
        ]["model_name"]
        return "bedrock" if "anthropic" in model else None

    callback = OpikTracer(provider=resolve_provider)

    _proxy_chat_model().invoke(
        "What is the capital of France?", config={"callbacks": [callback]}
    )
    callback.flush()

    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.provider == "bedrock"


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
def test_try_extract_response_cost(response_metadata, expected_cost):
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
