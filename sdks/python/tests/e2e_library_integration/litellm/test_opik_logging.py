import litellm

from opik import Opik, synchronization
from ...e2e import verifiers
from ...testlib import ANY_DICT, ANY_STRING


def test_litellm_opik_logging__happyflow(
    ensure_openai_configured,
    opik_client_unique_project_name: Opik,
):
    litellm.callbacks = ["opik"]

    def streaming_function(input):
        messages = [{"role": "user", "content": input}]
        response = litellm.completion(
            model="gpt-3.5-turbo",
            messages=messages,
            metadata={
                "opik": {
                    "tags": ["streaming-test"],
                    "project_name": opik_client_unique_project_name._project_name,
                },
            },
        )
        return response

    _response = streaming_function("Why is tracking and evaluation of LLMs important?")

    if not synchronization.until(
        function=lambda: (opik_client_unique_project_name.search_traces() is not None),
        allow_errors=True,
        max_try_seconds=30,
    ):
        raise AssertionError(
            f"Failed to get traces from project '{opik_client_unique_project_name._project_name}'"
        )

    traces = opik_client_unique_project_name.search_traces(truncate=False)
    spans = opik_client_unique_project_name.search_spans(truncate=False)

    assert len(traces) == 1
    assert len(spans) == 1

    verifiers.verify_trace(
        opik_client=opik_client_unique_project_name,
        trace_id=traces[0].id,
        name="chat.completion",
        metadata=ANY_DICT,
        input=[
            {
                "content": "Why is tracking and evaluation of LLMs important?",
                "role": "user",
            }
        ],
        output=ANY_DICT,
        tags=["openai", "streaming-test"],
        project_name=opik_client_unique_project_name._project_name,
        error_info=None,
    )

    verifiers.verify_span(
        opik_client=opik_client_unique_project_name,
        trace_id=traces[0].id,
        span_id=spans[0].id,
        parent_span_id=None,
        name=ANY_STRING(startswith="gpt-3.5-turbo-0125_chat.completion_"),
        metadata=ANY_DICT,
        input=[
            {
                "content": "Why is tracking and evaluation of LLMs important?",
                "role": "user",
            }
        ],
        output=ANY_DICT,
        tags=["openai", "streaming-test"],
        project_name=opik_client_unique_project_name._project_name,
        error_info=None,
        type="llm",
    )
