import litellm

from opik import Opik, synchronization
from ... import llm_constants
from ...e2e import verifiers
from ...testlib import ANY_DICT, ANY_STRING


from . import constants


def test_litellm_opik_logging__happyflow(
    ensure_openai_configured,
    opik_client: Opik,
    configure_e2e_tests_env_unique_project_name: str,
):
    litellm.callbacks = ["opik"]

    def streaming_function(input):
        messages = [{"role": "user", "content": input}]
        # The pinned litellm (<1.75) rejects a top-level `reasoning_effort` for
        # gpt-5-nano with UnsupportedParamsError; extra_body skips that check
        # and is still forwarded to OpenAI.
        response = litellm.completion(
            model=constants.MODEL_NAME,
            messages=messages,
            max_completion_tokens=64,
            extra_body={"reasoning_effort": llm_constants.OPENAI_REASONING_EFFORT},
            metadata={
                "opik": {
                    "tags": ["streaming-test"],
                },
            },
        )
        return response

    _response = streaming_function("Reply with one word: hello")

    if not synchronization.until(
        function=lambda: (len(opik_client.search_traces()) > 0),
        allow_errors=True,
        max_try_seconds=30,
    ):
        raise AssertionError(
            f"Failed to get traces from project '{configure_e2e_tests_env_unique_project_name}'"
        )

    traces = opik_client.search_traces(truncate=False)
    spans = opik_client.search_spans(truncate=False)

    assert len(traces) == 1
    assert len(spans) == 1

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=traces[0].id,
        name="chat.completion",
        metadata=ANY_DICT.containing({"created_from": "litellm"}),
        input=[
            {
                "content": "Reply with one word: hello",
                "role": "user",
            }
        ],
        output=ANY_DICT,
        tags=["openai", "streaming-test"],
        project_name=configure_e2e_tests_env_unique_project_name,
        error_info=None,
    )

    verifiers.verify_span(
        opik_client=opik_client,
        trace_id=traces[0].id,
        span_id=spans[0].id,
        parent_span_id=None,
        name=ANY_STRING.starting_with(constants.MODEL_NAME),
        metadata=ANY_DICT.containing({"created_from": "litellm"}),
        input=[
            {
                "content": "Reply with one word: hello",
                "role": "user",
            }
        ],
        output=ANY_DICT,
        tags=["openai", "streaming-test"],
        project_name=configure_e2e_tests_env_unique_project_name,
        error_info=None,
        type="llm",
    )
