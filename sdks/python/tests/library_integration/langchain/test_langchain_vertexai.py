import langchain_google_vertexai
import pytest
from langchain.prompts import PromptTemplate

from typing import Dict, Any
from opik.integrations.langchain.opik_tracer import OpikTracer
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
    assert_dict_has_keys,
)


pytestmark = pytest.mark.usefixtures("ensure_vertexai_configured")


def _assert_usage_validity(usage: Dict[str, Any]):
    REQUIRED_USAGE_KEYS = [
        "completion_tokens",
        "prompt_tokens",
        "total_tokens",
        "original_usage.total_token_count",
        "original_usage.candidates_token_count",
        "original_usage.prompt_token_count",
    ]

    assert_dict_has_keys(usage, REQUIRED_USAGE_KEYS)


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
    fake_backend,
    llm_model,
    expected_input_prompt,
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
                    {
                        "content": expected_input_prompt,
                        "additional_kwargs": {},
                        "response_metadata": {},
                        "type": "human",
                        "name": None,
                        "id": None,
                        "example": False,
                    }
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
        spans=[
            SpanModel(
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
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="custom-google-vertexai-llm-name",
                        input=expected_llm_span_input,
                        output=ANY_BUT_NONE,
                        metadata={
                            "batch_size": ANY_BUT_NONE,
                            "invocation_params": ANY_DICT,
                            "metadata": ANY_DICT,
                            "options": ANY_DICT,
                            "usage": ANY_DICT,
                            "created_from": "langchain",
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=ANY_DICT,
                        spans=[],
                        provider="google_vertexai",
                        model=ANY_STRING(startswith="gemini-2.0-flash"),
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    llm_call_span = fake_backend.trace_trees[0].spans[0].spans[-1]

    _assert_usage_validity(llm_call_span.usage)
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
