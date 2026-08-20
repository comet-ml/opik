import pydantic
import pytest
from google.adk import models as adk_models
from google.genai import types as genai_types

from opik.integrations.adk import helpers as adk_helpers
from opik.integrations.adk.patchers import llm_response_wrapper


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
def test_both_tracers_report_conversion_failure_at_error_level(tracer_module_name):
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
