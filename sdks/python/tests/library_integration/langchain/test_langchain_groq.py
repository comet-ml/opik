import langchain_groq
import pytest
from langchain_core.prompts import PromptTemplate

from opik.integrations.langchain import OpikTracer
from .constants import (
    EXPECTED_SHORT_OPENAI_USAGE_LOGGED_FORMAT,
)
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)


@pytest.mark.parametrize(
    "streaming",
    [False, True],
)
def test_langchain__openai_llm_is_used__token_usage_is_logged__happy_flow(
    fake_backend,
    ensure_groq_configured,
    streaming,
):
    llm_args = {
        "max_tokens": 10,
        "name": "custom-groq-llm-name",
        "model": "openai/gpt-oss-20b",
    }

    if streaming is True:
        llm_args["streaming"] = streaming

    llm = langchain_groq.ChatGroq(**llm_args)

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="RunnableSequence",
        project_name="Default Project",
        input={"title": "Documentary about Bigfoot in Paris"},
        output={"output": ANY_DICT},
        tags=["tag1", "tag2"],
        metadata={"a": "b", "created_from": "langchain"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={"output": ANY_DICT},
                tags=["tag1", "tag2"],
                metadata={"a": "b", "created_from": "langchain"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={
                            "output": {
                                "text": "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
                                "type": "StringPromptValue",
                            }
                        },
                        metadata={"created_from": "langchain"},
                        type="tool",
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        last_updated_at=ANY_BUT_NONE,
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="custom-groq-llm-name",
                        input={"messages": ANY_BUT_NONE},
                        output=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        usage=ANY_DICT.containing(
                            EXPECTED_SHORT_OPENAI_USAGE_LOGGED_FORMAT
                        ),
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        model="gpt-oss-20b",
                        provider="groq",
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
    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])
