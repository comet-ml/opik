import opik
from opik import opik_context
from opik import Opik, synchronization
from ...e2e import verifiers
from ...testlib import ANY_DICT, ANY_STRING

from opik.evaluation.models.litellm import litellm_chat_model
from . import constants


def test_litellm_chat_model__call_made_inside_another_span_with_user_defined_project_name__llm_span_assigned_to_user_defined_project(
    ensure_openai_configured,
    opik_client: Opik,
):
    tested = litellm_chat_model.LiteLLMChatModel(
        model_name=constants.MODEL_NAME,
    )
    ID_STORAGE = {}
    USER_DEFINED_PROJECT_NAME = "some-project-name"

    @opik.track(project_name=USER_DEFINED_PROJECT_NAME)
    def f(input):
        ID_STORAGE["f_span_id"] = opik_context.get_current_span_data().id
        ID_STORAGE["f_trace_id"] = opik_context.get_current_trace_data().id
        return tested.generate_string(input)

    f("Why is tracking and evaluation of LLMs important?")

    def wait_condition_checker():
        spans = opik_client.search_spans(
            project_name=USER_DEFINED_PROJECT_NAME,
            trace_id=ID_STORAGE["f_trace_id"],
            filter_string=f'name contains "{constants.MODEL_NAME}"',
        )
        return len(spans) > 0

    if not synchronization.until(
        function=wait_condition_checker,
        allow_errors=True,
        max_try_seconds=30,
    ):
        raise AssertionError(
            f"Failed to get traces from project '{USER_DEFINED_PROJECT_NAME}'"
        )

    llm_spans = opik_client.search_spans(
        project_name=USER_DEFINED_PROJECT_NAME,
        trace_id=ID_STORAGE["f_trace_id"],
        filter_string=f'name contains "{constants.MODEL_NAME}"',
    )
    assert len(llm_spans) == 1

    verifiers.verify_span(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_trace_id"],
        span_id=llm_spans[0].id,
        parent_span_id=ID_STORAGE["f_span_id"],
        name=ANY_STRING.starting_with(constants.MODEL_NAME),
        metadata=ANY_DICT.containing({"created_from": "litellm"}),
        input=[
            {
                "content": "Why is tracking and evaluation of LLMs important?",
                "role": "user",
            }
        ],
        output=ANY_DICT,
        tags=["openai"],
        project_name=USER_DEFINED_PROJECT_NAME,
        error_info=None,
        type="llm",
    )
