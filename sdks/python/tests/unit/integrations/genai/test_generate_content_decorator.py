from google.genai import types as genai_types

from opik.api_objects import span
from opik.integrations.genai import generate_content_decorator


def test_end_span_inputs_preprocessor__cost_callback__receives_response():
    response = genai_types.GenerateContentResponse(
        candidates=[
            genai_types.Candidate(
                content=genai_types.Content(
                    role="model", parts=[genai_types.Part(text="Minsk")]
                )
            )
        ],
        usage_metadata=genai_types.GenerateContentResponseUsageMetadata(
            prompt_token_count=8,
            candidates_token_count=1,
            thoughts_token_count=40,
            total_token_count=49,
        ),
        model_version="gemini-2.5-flash",
    )
    callback_responses = []

    def cost_callback(callback_response):
        callback_responses.append(callback_response)
        return callback_response.usage_metadata.total_token_count / 1_000_000

    decorator = generate_content_decorator.GenerateContentTrackDecorator(
        provider="google_ai", cost_callback=cost_callback
    )

    result = decorator._end_span_inputs_preprocessor(
        output=response,
        capture_output=True,
        current_span_data=span.SpanData(
            trace_id="test-trace", name="generate_content: gemini-2.5-flash"
        ),
    )

    assert callback_responses == [response]
    assert result.total_cost == 49 / 1_000_000
