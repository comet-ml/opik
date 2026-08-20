import asyncio
from typing import AsyncGenerator

import pydantic
import pytest
from google.adk import agents as adk_agents
from google.adk import models as adk_models
from google.adk import runners as adk_runners
from google.adk.models import base_llm, llm_request, llm_response
from google.adk.sessions import in_memory_session_service
from google.genai import types as genai_types

from opik.integrations.adk import OpikTracer
from opik.integrations.adk import helpers as adk_helpers
from opik.integrations.adk.patchers import llm_response_wrapper
from . import helpers
from ...testlib import ANY_BUT_NONE, ANY_DICT, SpanModel, TraceModel, assert_equal


def _generate_content_response() -> genai_types.GenerateContentResponse:
    return genai_types.GenerateContentResponse(
        candidates=[
            genai_types.Candidate(
                content=genai_types.Content(
                    role="model", parts=[genai_types.Part(text="hi")]
                )
            )
        ],
        usage_metadata=genai_types.GenerateContentResponseUsageMetadata(
            prompt_token_count=3, candidates_token_count=5, total_token_count=8
        ),
    )


def test_wrap_llm_response_create__response_stays_dumpable():
    """The usage we attach must not make the LlmResponse impossible to serialize.

    Attaching the genai pydantic model itself put an object with no serializer into
    ``custom_metadata`` (typed ``dict[str, Any]``), so dumping the LlmResponse raised
    "'MockValSer' object is not an instance of 'SchemaSerializer'". after_model_callback
    swallows that, and the LLM spans silently lost their output and usage.
    """
    generate_content_response = _generate_content_response()

    response = llm_response_wrapper._wrap_llm_response_create(
        generate_content_response,
        lambda _: adk_models.LlmResponse(
            content=generate_content_response.candidates[0].content
        ),
    )

    assert isinstance(response.custom_metadata["opik_usage"], dict)
    # The whole point: this must not raise.
    dumped = adk_helpers.convert_adk_base_model_to_dict(response)
    assert dumped["custom_metadata"]["opik_usage"]["total_token_count"] == 8


def test_pop_llm_usage_data__reads_the_attached_dict():
    """The reader must accept the dict form we now attach."""
    generate_content_response = _generate_content_response()
    response = llm_response_wrapper._wrap_llm_response_create(
        generate_content_response,
        lambda _: adk_models.LlmResponse(
            content=generate_content_response.candidates[0].content
        ),
    )
    dumped = adk_helpers.convert_adk_base_model_to_dict(response)

    usage_data = llm_response_wrapper.pop_llm_usage_data(
        dumped, adk_helpers.get_adk_provider()
    )

    assert usage_data is not None
    assert usage_data.opik_usage is not None
    # Assert the counts survived the round-trip, not merely that parsing returned
    # something - a dropped or defaulted count would otherwise pass unnoticed.
    provider_usage = usage_data.opik_usage.provider_usage.model_dump()
    assert provider_usage["prompt_token_count"] == 3
    assert provider_usage["candidates_token_count"] == 5
    assert provider_usage["total_token_count"] == 8


def test_wrap_llm_response_create__usage_class_not_rebuilt_yet__still_dumpable():
    """Pins the fix to the deferred-class state that actually caused the outage.

    Since google-genai 2.18.0 (googleapis/python-genai#2784) every genai model sets
    ``defer_build=True``, so a class still holds a ``MockValSer`` until something
    rebuilds it. Serializing such an instance through ``custom_metadata`` (typed
    ``dict[str, Any]``) takes pydantic's inference path, which downcasts to
    ``SchemaSerializer`` in Rust without firing the lazy rebuild hook a Python-level
    call would, and raises "'MockValSer' object is not an instance of
    'SchemaSerializer'" - ``fallback=str`` does not rescue it. Upstream, still open:
    pydantic/pydantic#13647.

    A throwaway subclass keeps this deterministic. The real usage class self-heals the
    moment any earlier test dumps it directly, so a test built on it passes against
    unfixed code depending on test order - which is exactly how this regression could
    slip back in unnoticed.
    """

    class _DeferredUsage(genai_types.GenerateContentResponseUsageMetadata):
        model_config = pydantic.ConfigDict(defer_build=True)

    assert not _DeferredUsage.__pydantic_complete__, (
        "the class must still be deferred for this test to exercise the bug"
    )

    generate_content_response = _generate_content_response()
    generate_content_response.usage_metadata = _DeferredUsage.model_construct(
        prompt_token_count=3, candidates_token_count=5, total_token_count=8
    )

    response = llm_response_wrapper._wrap_llm_response_create(
        generate_content_response,
        lambda _: adk_models.LlmResponse(
            content=generate_content_response.candidates[0].content
        ),
    )

    # Both serialization paths that broke: ours, and the one ADK itself uses.
    dumped = adk_helpers.convert_adk_base_model_to_dict(response)
    response.model_dump_json()

    assert dumped["custom_metadata"]["opik_usage"]["total_token_count"] == 8


@pytest.mark.parametrize(
    "tracer_module_name",
    ["opik_tracer", "legacy_opik_tracer"],
)
def test_tracer_conversion_failure__both_tracers__logs_at_error_level(
    tracer_module_name,
):
    """A conversion failure must not be swallowed at DEBUG in either tracer.

    When it happens the span is still logged, but without output or usage, and nothing
    else surfaces that - so it has to be loud enough for an operator to see. DEBUG here
    is why a week of silent span data loss went unnoticed while CI only went red
    because ADK crashed on its own. Both tracers share ``llm_response_wrapper``, so
    both need the same treatment.

    Asserted against the except-block itself. Driving the real callback would need
    span, context and registry state this module has no business assembling, and a test
    that rebuilds the logging call instead of executing it would pass no matter what
    the handler does.
    """
    import importlib
    import inspect
    import re

    tracer_module = importlib.import_module(
        f"opik.integrations.adk.{tracer_module_name}"
    )
    source = inspect.getsource(tracer_module)

    handler = re.search(
        r"except Exception[^:]*:\s*\n(?:\s*#.*\n)*\s*LOGGER\.(\w+)\(\s*\n?\s*"
        r"\"Error converting LlmResponse to dict",
        source,
    )
    assert handler, (
        f"{tracer_module_name}: could not find the conversion-failure handler"
    )
    assert handler.group(1) == "error", (
        f"{tracer_module_name}: conversion failure is logged at "
        f"LOGGER.{handler.group(1)}, expected LOGGER.error"
    )


class _DeferredUsageMetadata(genai_types.GenerateContentResponseUsageMetadata):
    """Usage whose class is still deferred, as google-genai >= 2.18.0 ships them.

    Module-level so the class is shared, but ``defer_build=True`` means it stays
    unbuilt until something dumps it - which is the state that broke production.
    """

    model_config = pydantic.ConfigDict(defer_build=True)


class _FakeUsageModel(base_llm.BaseLlm):
    """An ADK model that carries usage metadata without touching the network.

    Routes its response through the patched ``LlmResponse.create`` the real
    provider path uses, so the usage lands in ``custom_metadata`` exactly as it
    does in production - and carries a deferred usage class, so the agent run
    reproduces the failure rather than a healthy variant of it.
    """

    model: str = "fake-gemini"

    async def generate_content_async(
        self, request: llm_request.LlmRequest, stream: bool = False
    ) -> AsyncGenerator[llm_response.LlmResponse, None]:
        generate_content_response = genai_types.GenerateContentResponse(
            candidates=[
                genai_types.Candidate(
                    content=genai_types.Content(
                        role="model", parts=[genai_types.Part(text="sunny, 22C")]
                    ),
                    finish_reason=genai_types.FinishReason.STOP,
                )
            ],
        )
        generate_content_response.usage_metadata = (
            _DeferredUsageMetadata.model_construct(
                prompt_token_count=11, candidates_token_count=7, total_token_count=18
            )
        )
        generate_content_response.model_version = "fake-gemini-1.0"
        yield llm_response_wrapper._wrap_llm_response_create(
            generate_content_response, adk_models.LlmResponse.create
        )


def _run_fake_agent() -> None:
    """Drive a real ADK agent through the fake model, offline."""
    tracer = OpikTracer(project_name="adk-usage-test")
    agent = adk_agents.LlmAgent(
        name="weather_agent",
        model=_FakeUsageModel(),
        instruction="Answer the weather question.",
        before_agent_callback=tracer.before_agent_callback,
        after_agent_callback=tracer.after_agent_callback,
        before_model_callback=tracer.before_model_callback,
        after_model_callback=tracer.after_model_callback,
    )

    session_service = in_memory_session_service.InMemorySessionService()
    runner = adk_runners.Runner(
        agent=agent, app_name="usage-probe", session_service=session_service
    )

    async def _run() -> None:
        await session_service.create_session(
            app_name="usage-probe", user_id="u1", session_id="s1"
        )
        async for _ in runner.run_async(
            user_id="u1",
            session_id="s1",
            new_message=genai_types.Content(
                role="user", parts=[genai_types.Part(text="weather?")]
            ),
        ):
            pass

    asyncio.run(_run())
    tracer.flush()


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk_llm_span__deferred_usage_metadata__output_and_usage_reach_the_backend(
    fake_backend,
):
    """The user-facing symptom: the span the backend receives must carry both.

    Every other test here stops at ``pop_llm_usage_data`` returning the right
    counts, which is exactly what failed to protect us - the counts parsed fine,
    the exception was swallowed upstream of the assignment, and the span went out
    empty. So this asserts the recorded tree, not the parser.

    A fake model keeps it offline. What a live Gemini call adds on top is only
    whether the provider really returns usage metadata, not whether we record it.
    """
    _run_fake_agent()

    # Derived, not hardcoded: the provider depends on GOOGLE_GENAI_USE_VERTEXAI,
    # which CI sets and a local run usually does not.
    expected_provider = adk_helpers.get_adk_provider().value

    EXPECTED_LLM_OUTPUT = {
        "content": {"parts": [{"text": "sunny, 22C"}], "role": "model"},
        "model_version": "fake-gemini-1.0",
        "finish_reason": "STOP",
        "custom_metadata": {"provider": expected_provider},
        "usage_metadata": {
            "candidates_token_count": 7,
            "prompt_token_count": 11,
            "total_token_count": 18,
        },
        "avg_logprobs": None,
        "citation_metadata": None,
        "grounding_metadata": None,
        "logprobs_result": None,
    }

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_agent",
        input={"parts": [{"text": "weather?"}], "role": "user"},
        output=EXPECTED_LLM_OUTPUT,
        metadata=ANY_DICT,
        # ADK's session id becomes the Opik thread id.
        thread_id="s1",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="adk-usage-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="fake-gemini-1.0",
                type="llm",
                input=ANY_DICT,
                output=EXPECTED_LLM_OUTPUT,
                # The whole point of the ticket: not merely present, but correct.
                usage={
                    "completion_tokens": 7,
                    "prompt_tokens": 11,
                    "total_tokens": 18,
                    "original_usage.candidates_token_count": 7,
                    "original_usage.prompt_token_count": 11,
                    "original_usage.total_token_count": 18,
                },
                model="fake-gemini-1.0",
                provider=expected_provider,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                project_name="adk-usage-test",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
