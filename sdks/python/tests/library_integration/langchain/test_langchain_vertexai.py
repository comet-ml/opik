import langchain_google_vertexai
import pytest
from langchain_core.prompts import PromptTemplate

from opik.integrations.langchain.opik_tracer import OpikTracer
from . import google_helpers
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


pytestmark = pytest.mark.usefixtures("ensure_vertexai_configured")


@pytest.mark.parametrize(
    "llm_model, expected_input_prompt",
    [
        (
            langchain_google_vertexai.VertexAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
        ),
        (
            langchain_google_vertexai.ChatVertexAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
        ),
    ],
)
def test_langchain__google_vertexai_llm_is_used__token_usage_is_logged__happyflow(
    fake_backend, llm_model, expected_input_prompt
):
    llm = llm_model(
        max_tokens=10,
        model_name="gemini-2.0-flash",
        name="custom-google-vertexai-llm-name",
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    if llm_model == langchain_google_vertexai.VertexAI:
        expected_llm_span_input = {"prompts": [expected_input_prompt]}
    else:
        expected_llm_span_input = {
            "messages": [
                [
                    ANY_DICT.containing(
                        {
                            "content": expected_input_prompt,
                            "type": "human",
                        }
                    ),
                ]
            ]
        }

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ANY_BUT_NONE,
        tags=["tag1", "tag2"],
        metadata={
            "a": "b",
            "created_from": "langchain",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="tool",
                name="PromptTemplate",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={"output": ANY_BUT_NONE},
                metadata={
                    "created_from": "langchain",
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="custom-google-vertexai-llm-name",
                input=expected_llm_span_input,
                output=ANY_BUT_NONE,
                metadata=ANY_DICT.containing(
                    {"created_from": "langchain", "usage": ANY_DICT}
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=ANY_DICT,
                provider="google_vertexai",
                model=ANY_STRING.starting_with("gemini-2.0-flash"),
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    llm_call_span = fake_backend.trace_trees[0].spans[-1]

    google_helpers.assert_usage_validity(llm_call_span.usage)
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize(
    "llm_model, expected_input_prompt",
    [
        (
            langchain_google_vertexai.VertexAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
        ),
        (
            langchain_google_vertexai.ChatVertexAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
        ),
    ],
)
def test_langchain__google_vertexai_llm_is_used__streaming__token_usage_is_logged__happyflow(
    fake_backend, llm_model, expected_input_prompt
):
    llm = llm_model(
        max_tokens=10,
        model_name="gemini-2.0-flash",
        name="custom-google-vertexai-llm-name",
        streaming=True,
        stream_usage=True,
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    if llm_model == langchain_google_vertexai.VertexAI:
        expected_llm_span_input = {"prompts": [expected_input_prompt]}
    else:
        expected_llm_span_input = {
            "messages": [
                [
                    ANY_DICT.containing(
                        {
                            "content": expected_input_prompt,
                            "type": "human",
                        }
                    ),
                ]
            ]
        }

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ANY_BUT_NONE,
        tags=["tag1", "tag2"],
        metadata={
            "a": "b",
            "created_from": "langchain",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="tool",
                name="PromptTemplate",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={"output": ANY_BUT_NONE},
                metadata={
                    "created_from": "langchain",
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="custom-google-vertexai-llm-name",
                input=expected_llm_span_input,
                output=ANY_BUT_NONE,
                metadata=ANY_DICT.containing(
                    {"created_from": "langchain", "usage": ANY_DICT}
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=ANY_DICT,
                provider="google_vertexai",
                model=ANY_STRING.starting_with("gemini-2.0-flash"),
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    llm_call_span = fake_backend.trace_trees[0].spans[-1]

    google_helpers.assert_usage_validity(llm_call_span.usage)
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
