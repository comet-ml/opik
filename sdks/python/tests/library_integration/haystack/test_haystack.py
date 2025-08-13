import sys

import pytest
import opik.jsonable_encoder
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
    fake_backend,
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
                "template": opik.jsonable_encoder.encode(messages),
            }
        },
        output=ANY_DICT,
        tags=ANY,
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                model=ANY_STRING.starting_with("gpt-3.5-turbo"),
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_haystack__context_aware_tracing(fake_backend):
    """Test that Haystack pipeline creates spans within existing trace context"""
    import opik
    from haystack import Pipeline
    from haystack.components.builders import ChatPromptBuilder
    from haystack.components.generators.chat import OpenAIChatGenerator
    from haystack.dataclasses import ChatMessage
    from opik.integrations.haystack import OpikConnector
    from haystack.tracing import tracer

    @opik.track(name="External Trace", capture_output=True)
    def run_haystack_in_trace():
        # Now run a Haystack pipeline inside the trace
        opik_connector = OpikConnector("Nested Chat Pipeline")
        pipe = Pipeline()
        pipe.add_component("tracer", opik_connector)
        pipe.add_component("prompt_builder", ChatPromptBuilder())
        pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))

        pipe.connect("prompt_builder.prompt", "llm.messages")

        messages = [
            ChatMessage.from_system("You are a helpful assistant."),
            ChatMessage.from_user("Say hello to {{name}}"),
        ]

        pipe.run(
            data={
                "prompt_builder": {
                    "template_variables": {"name": "world"},
                    "template": messages,
                }
            }
        )

        tracer.actual_tracer.flush()
        return "pipeline completed"

    run_haystack_in_trace()
    opik.flush_tracker()

    # Verify we have exactly one trace tree
    assert len(fake_backend.trace_trees) == 1
    
    # Build expected trace structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="External Trace",
        input=ANY_DICT,
        output={"output": "pipeline completed"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="Default Project",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="External Trace",
                type="general",
                input=ANY_DICT,
                output={"output": "pipeline completed"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="Nested Chat Pipeline",
                type="general",
                input=ANY_DICT,  # Contains pipeline input data
                output=ANY_DICT,  # Contains pipeline output data  
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                metadata=ANY_DICT,  # Contains haystack metadata
                spans=[
                    # Haystack creates child spans for each component
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="prompt_builder",
                        type="general",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        metadata=ANY_DICT,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="tracer",
                        type="general",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        metadata=ANY_DICT,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="llm",
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        metadata=ANY_DICT,
                        usage=ANY_DICT,
                        model=ANY_STRING,
                    ),
                ],
            ),
        ],
    )
    
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_haystack__no_duplicate_traces_when_using_existing_context(fake_backend):
    """Test that Haystack pipeline doesn't create a separate trace when there's already a context"""
    import opik
    from haystack import Pipeline
    from haystack.components.builders import ChatPromptBuilder
    from haystack.components.generators.chat import OpenAIChatGenerator
    from haystack.dataclasses import ChatMessage
    from opik.integrations.haystack import OpikConnector
    from haystack.tracing import tracer

    @opik.track(name="External Trace", capture_output=True)
    def run_haystack_with_different_project():
        # Run Haystack pipeline with different project name but expect it to respect existing context
        opik_connector = OpikConnector("Pipeline with Different Project", project_name="different-project")
        pipe = Pipeline()
        pipe.add_component("tracer", opik_connector)
        pipe.add_component("prompt_builder", ChatPromptBuilder())
        pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))

        pipe.connect("prompt_builder.prompt", "llm.messages")

        messages = [
            ChatMessage.from_system("You are a helpful assistant."),
            ChatMessage.from_user("What is 2+2?"),
        ]

        pipe.run(
            data={
                "prompt_builder": {
                    "template_variables": {},
                    "template": messages,
                }
            }
        )

        tracer.actual_tracer.flush()
        return "completed"

    run_haystack_with_different_project()
    opik.flush_tracker()

    # Verify we have exactly one trace tree
    assert len(fake_backend.trace_trees) == 1
    
    # Build expected trace structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="External Trace",
        input=ANY_DICT,
        output={"output": "completed"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="Default Project",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="External Trace",
                type="general",
                input=ANY_DICT,
                output={"output": "completed"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="Pipeline with Different Project",
                type="general",
                input=ANY_DICT,  # Contains pipeline input data
                output=ANY_DICT,  # Contains pipeline output data
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="different-project",  # Uses the project from OpikConnector since new client is created
                metadata=ANY_DICT,  # Contains haystack metadata
                spans=[
                    # Haystack creates child spans for each component
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="prompt_builder",
                        type="general",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name="different-project",
                        metadata=ANY_DICT,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="tracer",
                        type="general",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name="different-project",
                        metadata=ANY_DICT,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="llm",
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name="different-project",
                        metadata=ANY_DICT,
                        usage=ANY_DICT,
                        model=ANY_STRING,
                    ),
                ],
            ),
        ],
    )
    
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
