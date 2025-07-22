import pytest
import langchain_google_genai
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
    patch_environ,
)

pytestmark = pytest.mark.usefixtures("ensure_google_api_configured")


def _assert_usage_validity(usage: Dict[str, Any]):
    REQUIRED_USAGE_KEYS = [
        "completion_tokens",
        "prompt_tokens",
        "total_tokens",
        "original_usage.input_tokens",
        "original_usage.output_tokens",
        "original_usage.total_tokens",
        "original_usage.input_token_details.cache_read",
    ]

    assert_dict_has_keys(usage, REQUIRED_USAGE_KEYS)


@pytest.mark.parametrize(
    "llm_model, expected_input_prompt, assert_helper",
    [
        (
            langchain_google_genai.GoogleGenerativeAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
            _assert_usage_validity,
        ),
        (
            langchain_google_genai.ChatGoogleGenerativeAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
            _assert_usage_validity,
        ),
    ],
)
def test_langchain__google_genai_llm_is_used__token_usage_is_logged__happy_flow(
    fake_backend, llm_model, expected_input_prompt, assert_helper
):
    with patch_environ(add_keys={"GOOGLE_GENAI_USE_VERTEXAI": "FALSE"}):
        llm = llm_model(
            max_tokens=10,
            model="gemini-2.0-flash",
            name="custom-google-genai-llm-name",
        )

        template = "Given the title of play, write a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

        callback.flush()

    if llm_model == langchain_google_genai.GoogleGenerativeAI:
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
        start_time=ANY_BUT_NONE,
        name="RunnableSequence",
        project_name="Default Project",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ANY_BUT_NONE,
        tags=["tag1", "tag2"],
        metadata={"a": "b", "created_from": "langchain"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=ANY_BUT_NONE,
                tags=["tag1", "tag2"],
                metadata={"a": "b", "created_from": "langchain"},
                type="general",
                usage=None,
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={"created_from": "langchain"},
                        type="tool",
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        last_updated_at=ANY_BUT_NONE,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="custom-google-genai-llm-name",
                        input=expected_llm_span_input,
                        output=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        usage=ANY_DICT,
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        model=ANY_STRING.starting_with("gemini-2.0-flash"),
                        provider="google_ai",
                        last_updated_at=ANY_BUT_NONE,
                    ),
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    llm_call_span = fake_backend.trace_trees[0].spans[0].spans[-1]

    assert_helper(llm_call_span.usage)
    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])
