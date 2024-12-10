import os

from opik.config import OPIK_PROJECT_DEFAULT_NAME
import pytest


os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true"

from haystack import Pipeline
from haystack.components.builders import ChatPromptBuilder
from haystack.components.generators.chat import OpenAIChatGenerator
from haystack.dataclasses import ChatMessage
from opik.integrations.haystack import (
    OpikConnector,
)


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
    project_name,
    expected_project_name,
):
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

    response = pipe.run(
        data={
            "prompt_builder": {
                "template_variables": {"location": "Berlin"},
                "template": messages,
            }
        }
    )
    print(response["llm"]["replies"][0])
    print(response["tracer"]["trace_url"])

    # EXPECTED_TRACE_TREE = TraceModel(
    #     id=ANY_BUT_NONE,
    #     name="RunnableSequence",
    #     input={"title": "Documentary about Bigfoot in Paris"},
    #     output={
    #         "output": "I'm sorry, I don't think I'm talented enough to write a synopsis"
    #     },
    #     tags=["tag1", "tag2"],
    #     metadata={"a": "b"},
    #     start_time=ANY_BUT_NONE,
    #     end_time=ANY_BUT_NONE,
    #     project_name=expected_project_name,
    #     spans=[
    #         SpanModel(
    #             id=ANY_BUT_NONE,
    #             name="RunnableSequence",
    #             input={"title": "Documentary about Bigfoot in Paris"},
    #             output=ANY_DICT,
    #             tags=["tag1", "tag2"],
    #             metadata={"a": "b"},
    #             start_time=ANY_BUT_NONE,
    #             end_time=ANY_BUT_NONE,
    #             project_name=expected_project_name,
    #             spans=[
    #                 SpanModel(
    #                     id=ANY_BUT_NONE,
    #                     type="general",
    #                     name="PromptTemplate",
    #                     input={"title": "Documentary about Bigfoot in Paris"},
    #                     output=ANY_DICT,
    #                     metadata={},
    #                     start_time=ANY_BUT_NONE,
    #                     end_time=ANY_BUT_NONE,
    #                     project_name=expected_project_name,
    #                     spans=[],
    #                 ),
    #                 SpanModel(
    #                     id=ANY_BUT_NONE,
    #                     type="llm",
    #                     name="FakeListLLM",
    #                     input={
    #                         "prompts": [
    #                             "Given the title of play, right a synopsys for that. Title: Documentary about Bigfoot in Paris."
    #                         ]
    #                     },
    #                     output=ANY_DICT,
    #                     metadata={
    #                         "invocation_params": {
    #                             "responses": [
    #                                 "I'm sorry, I don't think I'm talented enough to write a synopsis"
    #                             ],
    #                             "_type": "fake-list",
    #                             "stop": None,
    #                         },
    #                         "options": {"stop": None},
    #                         "batch_size": 1,
    #                         "metadata": ANY_BUT_NONE,
    #                     },
    #                     start_time=ANY_BUT_NONE,
    #                     end_time=ANY_BUT_NONE,
    #                     project_name=expected_project_name,
    #                     spans=[],
    #                 ),
    #             ],
    #         )
    #     ],
    # )

    assert len(fake_backend.trace_trees) == 1
    # assert len(callback.created_traces()) == 1
    # assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
