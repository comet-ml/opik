import opik
from opik import opik_context
from opik import Opik, synchronization
from ...e2e import verifiers
from ...testlib import ANY_DICT, ANY_STRING

from opik.evaluation.models.litellm import litellm_chat_model
from . import constants


def test_litellm_chat_model__call_made_inside_another_span__project_name_is_set_in_env__llm_span_assigned_to_configured_project(
    ensure_openai_configured,
    opik_client: Opik,
    configure_e2e_tests_env_unique_project_name: str,
):
    tested = litellm_chat_model.LiteLLMChatModel(
        model_name=constants.MODEL_NAME,
    )
    ID_STORAGE = {}

    @opik.track
    def f(input):
        ID_STORAGE["f_span_id"] = opik_context.get_current_span_data().id
        ID_STORAGE["f_trace_id"] = opik_context.get_current_trace_data().id
        return tested.generate_string(input)

    f("Why is tracking and evaluation of LLMs important?")

    def wait_condition_checker():
        spans = opik_client.search_spans(
            project_name=configure_e2e_tests_env_unique_project_name,
            trace_id=ID_STORAGE["f_trace_id"],
            filter_string=f'name contains "{constants.MODEL_NAME}"',
        )
        return len(spans) > 0

    if not synchronization.until(
        function=wait_condition_checker,
        allow_errors=True,
        max_try_seconds=10,
    ):
        raise AssertionError(
            f"Failed to get spans from project '{configure_e2e_tests_env_unique_project_name}'"
        )

    llm_spans = opik_client.search_spans(
        project_name=configure_e2e_tests_env_unique_project_name,
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
        project_name=configure_e2e_tests_env_unique_project_name,
        error_info=None,
        type="llm",
    )


def test_litellm_chat_model__call_made_inside_another_span__project_name_is_set_in_env__llm_span_assigned_to_configured_project_2(
    ensure_openai_configured,
    opik_client: Opik,
    configure_e2e_tests_env_unique_project_name: str,
):
    tested = litellm_chat_model.LiteLLMChatModel(
        model_name=constants.MODEL_NAME,
    )
    ID_STORAGE = {}

    @opik.track
    def f(input):
        ID_STORAGE["f_span_id"] = opik_context.get_current_span_data().id
        ID_STORAGE["f_trace_id"] = opik_context.get_current_trace_data().id
        return tested.generate_string(input)

    f("Why is tracking and evaluation of LLMs important?")

    def wait_condition_checker():
        spans = opik_client.search_spans(
            project_name=configure_e2e_tests_env_unique_project_name,
            trace_id=ID_STORAGE["f_trace_id"],
            filter_string=f'name contains "{constants.MODEL_NAME}"',
        )
        return len(spans) > 0

    if not synchronization.until(
        function=wait_condition_checker,
        allow_errors=True,
        max_try_seconds=10,
    ):
        raise AssertionError(
            f"Failed to get spans from project '{configure_e2e_tests_env_unique_project_name}'"
        )

    llm_spans = opik_client.search_spans(
        project_name=configure_e2e_tests_env_unique_project_name,
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
        project_name=configure_e2e_tests_env_unique_project_name,
        error_info=None,
        type="llm",
    )


def test_litellm_chat_model__call_made_inside_another_span__project_name_is_in_decorator_explicitly__llm_span_assigned_to_configured_project(
    ensure_openai_configured,
    opik_client: Opik,
    configure_e2e_tests_env_unique_project_name: str,
):
    tested = litellm_chat_model.LiteLLMChatModel(
        model_name=constants.MODEL_NAME,
    )
    ID_STORAGE = {}
    PROJECT_NAME = "some-project-name"

    @opik.track(project_name=PROJECT_NAME)
    def f(input):
        ID_STORAGE["f_span_id"] = opik_context.get_current_span_data().id
        ID_STORAGE["f_trace_id"] = opik_context.get_current_trace_data().id
        return tested.generate_string(input)

    f("Why is tracking and evaluation of LLMs important?")
    opik.flush_tracker()

    def wait_condition_checker():
        spans = opik_client.search_spans(
            project_name=PROJECT_NAME,
            trace_id=ID_STORAGE["f_trace_id"],
            filter_string=f'name contains "{constants.MODEL_NAME}"',
        )
        return len(spans) > 0

    if not synchronization.until(
        function=wait_condition_checker,
        allow_errors=True,
        max_try_seconds=10,
    ):
        raise AssertionError(f"Failed to get spans from project '{PROJECT_NAME}'")

    llm_spans = opik_client.search_spans(
        project_name=PROJECT_NAME,
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
        project_name=PROJECT_NAME,
        error_info=None,
        type="llm",
    )
