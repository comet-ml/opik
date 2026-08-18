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
