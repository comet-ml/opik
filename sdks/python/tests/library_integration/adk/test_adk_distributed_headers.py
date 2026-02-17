import pytest
from google.genai import types as genai_types

import opik
from opik import opik_context
from opik.integrations.adk import OpikTracer
from opik.integrations.adk import helpers as opik_adk_helpers
from . import constants, helpers
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__distributed_headers__sequential_agent_with_subagents__happy_flow(
    fake_backend,
):
    # create root trace/span
    with opik.start_as_current_trace("parent-trace", flush=False):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    opik_tracer = OpikTracer(distributed_headers=distributed_headers)
    root_agent = helpers.root_agent_sequential_with_translator_and_summarizer(
        opik_tracer
    )
    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=constants.USER_ID,
        session_id=constants.SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="parent-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="parent-span",
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="TextProcessingAssistant",
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata={
                            "created_from": "google-adk",
                            "adk_invocation_id": ANY_STRING,
                            "app_name": constants.APP_NAME,
                            "user_id": constants.USER_ID,
                            "_opik_graph_definition": ANY_BUT_NONE,
                        },
                        output=ANY_DICT.containing(
                            {
                                "content": {
                                    "parts": [{"text": final_response}],
                                    "role": "model",
                                }
                            }
                        ),
                        input={
                            "role": "user",
                            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
                        },
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="Translator",
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                last_updated_at=ANY_BUT_NONE,
                                metadata=ANY_DICT,
                                type="general",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        name=constants.MODEL_NAME,
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        last_updated_at=ANY_BUT_NONE,
                                        metadata=ANY_DICT,
                                        type="llm",
                                        input=ANY_DICT,
                                        output=ANY_DICT,
                                        provider=opik_adk_helpers.get_adk_provider(),
                                        model=constants.MODEL_NAME,
                                        usage=ANY_DICT,
                                        ttft=ANY_BUT_NONE,
                                    )
                                ],
                            ),
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="Summarizer",
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                last_updated_at=ANY_BUT_NONE,
                                metadata=ANY_DICT,
                                type="general",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        name=constants.MODEL_NAME,
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        last_updated_at=ANY_BUT_NONE,
                                        metadata=ANY_DICT,
                                        type="llm",
                                        input=ANY_DICT,
                                        output=ANY_DICT,
                                        provider=opik_adk_helpers.get_adk_provider(),
                                        model=constants.MODEL_NAME,
                                        usage=ANY_DICT,
                                        ttft=ANY_BUT_NONE,
                                    )
                                ],
                            ),
                        ],
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    translator_span = trace_tree.spans[0].spans[0].spans[0]
    assert_dict_has_keys(
        translator_span.spans[0].usage, constants.EXPECTED_USAGE_KEYS_GOOGLE
    )

    summarizer_span = trace_tree.spans[0].spans[0].spans[1]
    assert_dict_has_keys(
        summarizer_span.spans[0].usage, constants.EXPECTED_USAGE_KEYS_GOOGLE
    )


@helpers.pytest_skip_for_adk_older_than_1_3_0
@pytest.mark.asyncio
async def test_adk__distributed_headers__sequential_agent_with_subagents__happy_flow_async(
    fake_backend,
):
    # create root trace/span
    with opik.start_as_current_trace("parent-trace", flush=False):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    opik_tracer = OpikTracer(distributed_headers=distributed_headers)
    root_agent = helpers.root_agent_sequential_with_translator_and_summarizer(
        opik_tracer
    )
    runner = await helpers.async_build_runner(root_agent)

    events_generator = runner.run_async(
        user_id=constants.USER_ID,
        session_id=constants.SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = await helpers.async_extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="parent-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="parent-span",
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="TextProcessingAssistant",
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata={
                            "created_from": "google-adk",
                            "adk_invocation_id": ANY_STRING,
                            "app_name": constants.APP_NAME,
                            "user_id": constants.USER_ID,
                            "_opik_graph_definition": ANY_DICT,
                        },
                        output=ANY_DICT.containing(
                            {
                                "content": {
                                    "parts": [{"text": final_response}],
                                    "role": "model",
                                }
                            }
                        ),
                        input={
                            "role": "user",
                            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
                        },
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="Translator",
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                last_updated_at=ANY_BUT_NONE,
                                metadata=ANY_DICT,
                                type="general",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        name=constants.MODEL_NAME,
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        last_updated_at=ANY_BUT_NONE,
                                        metadata=ANY_DICT,
                                        type="llm",
                                        input=ANY_DICT,
                                        output=ANY_DICT,
                                        provider=opik_adk_helpers.get_adk_provider(),
                                        model=constants.MODEL_NAME,
                                        usage=ANY_DICT,
                                        ttft=ANY_BUT_NONE,
                                    )
                                ],
                            ),
                            SpanModel(
                                id=ANY_BUT_NONE,
                                name="Summarizer",
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                last_updated_at=ANY_BUT_NONE,
                                metadata=ANY_DICT,
                                type="general",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        name=constants.MODEL_NAME,
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        last_updated_at=ANY_BUT_NONE,
                                        metadata=ANY_DICT,
                                        type="llm",
                                        input=ANY_DICT,
                                        output=ANY_DICT,
                                        provider=opik_adk_helpers.get_adk_provider(),
                                        model=constants.MODEL_NAME,
                                        usage=ANY_DICT,
                                        ttft=ANY_BUT_NONE,
                                    )
                                ],
                            ),
                        ],
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    translator_span = trace_tree.spans[0].spans[0].spans[0]
    assert_dict_has_keys(
        translator_span.spans[0].usage, constants.EXPECTED_USAGE_KEYS_GOOGLE
    )

    summarizer_span = trace_tree.spans[0].spans[0].spans[1]
    assert_dict_has_keys(
        summarizer_span.spans[0].usage, constants.EXPECTED_USAGE_KEYS_GOOGLE
    )
