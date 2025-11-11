import pytest
import pydantic

import opik
from opik.evaluation.models.litellm import litellm_chat_model
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)

from . import constants

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")

MODEL_FOR_TESTS = constants.MODEL_FOR_TESTS


def test_litellm_chat_model_generate_string__happyflow(fake_backend, monkeypatch):
    monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "true")

    model = litellm_chat_model.LiteLLMChatModel(model_name=MODEL_FOR_TESTS)
    result = model.generate_string("Tell me a short fact about Python programming")
    opik.flush_tracker()

    assert isinstance(result, str)
    assert len(result) > 0

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": ANY_BUT_NONE},
        output={"choices": ANY_BUT_NONE},
        tags=["litellm"],
        metadata=ANY_DICT.containing({"created_from": "litellm"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="completion",
                input={"messages": ANY_BUT_NONE},
                output={"choices": ANY_BUT_NONE},
                tags=["litellm"],
                metadata=ANY_DICT.containing({"created_from": "litellm"}),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_chat_model_nested_in_track__creates_child_span(
    fake_backend, monkeypatch
):
    monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "true")

    model = litellm_chat_model.LiteLLMChatModel(model_name=MODEL_FOR_TESTS)

    @opik.track
    def outer_function(text: str) -> str:
        return model.generate_string(text)

    result = outer_function("What is machine learning?")
    opik.flush_tracker()

    assert isinstance(result, str)
    assert len(result) > 0

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="outer_function",
        input={"text": "What is machine learning?"},
        output={"output": ANY_STRING},
        tags=None,
        metadata=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="outer_function",
                input={"text": "What is machine learning?"},
                output={"output": ANY_STRING},
                tags=None,
                metadata=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="completion",
                        input={"messages": ANY_BUT_NONE},
                        output={"choices": ANY_BUT_NONE},
                        tags=["litellm"],
                        metadata=ANY_DICT.containing({"created_from": "litellm"}),
                        usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                        total_cost=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                        provider="openai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.asyncio
async def test_litellm_chat_model_agenerate_string__happyflow(
    fake_backend, monkeypatch
):
    monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "true")

    model = litellm_chat_model.LiteLLMChatModel(model_name=MODEL_FOR_TESTS)
    result = await model.agenerate_string(
        "Tell me a short fact about async programming"
    )
    opik.flush_tracker()

    assert isinstance(result, str)
    assert len(result) > 0

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="acompletion",
        input={"messages": ANY_BUT_NONE},
        output={"choices": ANY_BUT_NONE},
        tags=["litellm"],
        metadata=ANY_DICT.containing({"created_from": "litellm"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="acompletion",
                input={"messages": ANY_BUT_NONE},
                output={"choices": ANY_BUT_NONE},
                tags=["litellm"],
                metadata=ANY_DICT.containing({"created_from": "litellm"}),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_chat_model_with_response_format__structured_output(
    fake_backend, monkeypatch
):
    monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "true")

    class SimpleResponse(pydantic.BaseModel):
        answer: str

    model = litellm_chat_model.LiteLLMChatModel(model_name=MODEL_FOR_TESTS)
    result = model.generate_string(
        "What is 2+2? Answer in one word.", response_format=SimpleResponse
    )
    opik.flush_tracker()

    assert isinstance(result, str)
    assert len(result) > 0

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input=ANY_DICT.containing({"messages": ANY_BUT_NONE}),
        output={"choices": ANY_BUT_NONE},
        tags=["litellm"],
        metadata=ANY_DICT.containing({"created_from": "litellm"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="completion",
                input=ANY_DICT.containing({"messages": ANY_BUT_NONE}),
                output={"choices": ANY_BUT_NONE},
                tags=["litellm"],
                metadata=ANY_DICT.containing({"created_from": "litellm"}),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_chat_model_with_monitoring_disabled__no_traces_created(
    fake_backend, monkeypatch
):
    monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

    model = litellm_chat_model.LiteLLMChatModel(model_name=MODEL_FOR_TESTS)
    result = model.generate_string("Tell me a short fact")
    opik.flush_tracker()

    assert isinstance(result, str)
    assert len(result) > 0
    assert len(fake_backend.trace_trees) == 0
