import os
import time
from opik.config import OPIK_PROJECT_DEFAULT_NAME
import pytest

from ...testlib import (
    ANY,
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    assert_equal,
)

from haystack import Pipeline
from haystack.components.builders import ChatPromptBuilder
from haystack.components.generators.chat import OpenAIChatGenerator
from haystack.dataclasses import ChatMessage
from opik.integrations.haystack import (
    OpikConnector,
)
from haystack.tracing import tracer


@pytest.fixture()
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")


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
    os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true"

    pipe = Pipeline()
    pipe.add_component(
        "tracer", OpikConnector("Chat example", project_name=project_name)
    )
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

    time.sleep(2)
    tracer.actual_tracer.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="Chat example",
        input=ANY,
        output=ANY,
        tags=ANY,
        metadata=ANY,
        start_time=ANY_BUT_NONE,
        end_time=ANY,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="tracer",
                input=ANY,
                output=ANY,
                tags=ANY,
                metadata=ANY,
                start_time=ANY_BUT_NONE,
                end_time=ANY,
                project_name=expected_project_name,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="prompt_builder",
                input=ANY,
                output=ANY,
                tags=ANY,
                metadata=ANY,
                start_time=ANY_BUT_NONE,
                end_time=ANY,
                project_name=expected_project_name,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="llm",
                type="llm",
                input=ANY,
                output=ANY,
                tags=ANY,
                metadata=ANY,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
            ),
        ],
    )

    print(fake_backend.trace_trees[0])

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
