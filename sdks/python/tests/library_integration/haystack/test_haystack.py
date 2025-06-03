import sys

import pytest

from opik.config import OPIK_PROJECT_DEFAULT_NAME
from ...testlib import (
    ANY,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    assert_equal,
    patch_environ,
)


@pytest.fixture(autouse=True, scope="module")
def enable_haystack_content_tracing():
    assert (
        "haystack" not in sys.modules
    ), "haystack must be imported only after content tracing env var is set"
    with patch_environ({"HAYSTACK_CONTENT_TRACING_ENABLED": "true"}):
        yield


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("haystack-integration-test", "haystack-integration-test"),
    ],
)
def test_haystack__happyflow(
    fake_backend_without_batching,
    project_name,
    expected_project_name,
):
    from haystack import Pipeline
    from haystack.components.builders import ChatPromptBuilder
    from haystack.components.generators.chat import OpenAIChatGenerator
    from haystack.dataclasses import ChatMessage
    from opik.integrations.haystack import (
        OpikConnector,
    )
    from haystack.tracing import tracer

    opik_connector = OpikConnector("Chat example", project_name=project_name)
    pipe = Pipeline()
    pipe.add_component("tracer", opik_connector)  # not necessary to add
    pipe.add_component("prompt_builder", ChatPromptBuilder())
    pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))

    pipe.connect("prompt_builder.prompt", "llm.messages")

    messages = [
        ChatMessage.from_system(
            "Always respond in German even if some input data is in other languages."
        ),
        ChatMessage.from_user("Tell me about {{location}}"),
    ]

    pipe.run(
        data={
            "prompt_builder": {
                "template_variables": {"location": "Berlin"},
                "template": messages,
            }
        }
    )

    tracer.actual_tracer.flush()

    # The tracer and prompt_builder components are not dependent on any other components
    # so they will be executed first. The order of execution is alphabetical: prompt_builder first, then tracer.
    # In fact, tracer may even be not added to the pipeline to generate opik spans/traces,
    # because the tracing itself is being set up inside OpikConnector.__init__ call.
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="Chat example",
        input={
            "prompt_builder": {
                "template_variables": {"location": "Berlin"},
                "template": messages,
            }
        },
        output=ANY_DICT,
        tags=ANY,
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="prompt_builder",
                input=ANY_DICT,
                output=ANY_DICT,
                tags=ANY,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="tracer",
                input=ANY_DICT,
                output=ANY_DICT,
                tags=ANY,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="llm",
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                tags=ANY,
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                usage={
                    "prompt_tokens": ANY_BUT_NONE,
                    "completion_tokens": ANY_BUT_NONE,
                    "total_tokens": ANY_BUT_NONE,
                    "original_usage.prompt_tokens": ANY_BUT_NONE,
                    "original_usage.completion_tokens": ANY_BUT_NONE,
                    "original_usage.total_tokens": ANY_BUT_NONE,
                    "original_usage.completion_tokens_details.accepted_prediction_tokens": ANY_BUT_NONE,
                    "original_usage.completion_tokens_details.audio_tokens": ANY_BUT_NONE,
                    "original_usage.completion_tokens_details.reasoning_tokens": ANY_BUT_NONE,
                    "original_usage.completion_tokens_details.rejected_prediction_tokens": ANY_BUT_NONE,
                    "original_usage.prompt_tokens_details.audio_tokens": ANY_BUT_NONE,
                    "original_usage.prompt_tokens_details.cached_tokens": ANY_BUT_NONE,
                },
                model=ANY_STRING(startswith="gpt-3.5-turbo"),
            ),
        ],
    )

    assert len(fake_backend_without_batching.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend_without_batching.trace_trees[0])
