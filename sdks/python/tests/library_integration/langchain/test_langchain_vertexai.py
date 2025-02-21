import langchain_google_vertexai
import pytest
from langchain.prompts import PromptTemplate

from opik.integrations.langchain.opik_tracer import OpikTracer
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
    "llm_model, expected_input_prompt, metadata_usage",
    [
        (
            langchain_google_vertexai.VertexAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
            {
                # openai format
                "completion_tokens": ANY_BUT_NONE,
                "prompt_tokens": ANY_BUT_NONE,
                "total_tokens": ANY_BUT_NONE,
                # VertexAI format
                # "cached_content_token_count": ANY_BUT_NONE,
                "candidates_token_count": ANY_BUT_NONE,
                "prompt_token_count": ANY_BUT_NONE,
                "total_token_count": ANY_BUT_NONE,
            },
        ),
        (
            langchain_google_vertexai.ChatVertexAI,
            "Human: Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
            {
                # openai format
                "completion_tokens": ANY_BUT_NONE,
                "prompt_tokens": ANY_BUT_NONE,
                "total_tokens": ANY_BUT_NONE,
                # ChatVertexAI format
                "cached_content_token_count": ANY_BUT_NONE,
                "candidates_token_count": ANY_BUT_NONE,
                "prompt_token_count": ANY_BUT_NONE,
                "total_token_count": ANY_BUT_NONE,
            },
        ),
    ],
)
def test_langchain__google_vertexai_llm_is_used__token_usage_is_logged__happyflow(
    fake_backend,
    llm_model,
    expected_input_prompt,
    metadata_usage,
):
    llm = llm_model(
        max_tokens=10,
        model_name="gemini-1.5-flash",
        name="custom-google-vertexai-llm-name",
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ANY_BUT_NONE,
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=ANY_BUT_NONE,
                tags=["tag1", "tag2"],
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="tool",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="custom-google-vertexai-llm-name",
                        input={"prompts": [expected_input_prompt]},
                        output=ANY_BUT_NONE,
                        metadata={
                            "batch_size": ANY_BUT_NONE,
                            "invocation_params": ANY_DICT,
                            "metadata": ANY_DICT,
                            "options": ANY_DICT,
                            "usage": metadata_usage,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=metadata_usage,
                        spans=[],
                        provider="google_vertexai",
                        model=ANY_STRING(startswith="gemini-1.5-flash"),
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
