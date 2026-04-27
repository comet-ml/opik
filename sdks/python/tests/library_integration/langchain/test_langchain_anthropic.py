import langchain_anthropic
import pytest
from langchain_core.prompts import PromptTemplate

from opik.integrations.langchain.opik_tracer import OpikTracer
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


pytestmark = pytest.mark.usefixtures("ensure_anthropic_configured")


EXPECTED_USAGE_ANTHROPIC = ANY_DICT.containing(
    {
        "completion_tokens": ANY,
        "prompt_tokens": ANY,
        "total_tokens": ANY,
        "original_usage.input_tokens": ANY,
        "original_usage.output_tokens": ANY,
        "original_usage.cache_creation_input_tokens": ANY,
        "original_usage.cache_read_input_tokens": ANY,
    }
)


MODEL_FOR_TESTS_FULL = "claude-sonnet-4-0"
MODEL_FOR_TESTS_SHORT = "claude-sonnet-4"


def test_langchain__anthropic_chat_is_used__token_usage_and_provider_is_logged__happyflow(
    fake_backend,
):
    # langchain_anthropic.Anthropic/AnthropicLLM is not tested because it is considered a legacy API which does not support the newest models
    llm = langchain_anthropic.ChatAnthropic(
        max_tokens=100,
        model_name=MODEL_FOR_TESTS_FULL,
        name="custom-anthropic-llm-name",
    )

    template = (
        "Given the title of play, write a short synopsys for that. Title: {title}."
    )

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
                source="sdk",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="custom-anthropic-llm-name",
                input={
                    "messages": [
                        [
                            ANY_DICT.containing(
                                {
                                    "content": "Given the title of play, write a short synopsys for that. Title: Documentary about Bigfoot in Paris.",
                                    "type": "human",
                                }
                            ),
                        ]
                    ]
                },
                output=ANY_BUT_NONE,
                metadata=ANY_DICT.containing(
                    {"created_from": "langchain", "usage": ANY_DICT}
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_USAGE_ANTHROPIC,
                provider="anthropic",
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langchain__anthropic_chat_is_used__streaming_mode__token_usage_and_provider_is_logged__happyflow(
    fake_backend,
):
    # langchain_anthropic.Anthropic/AnthropicLLM is not tested because it is considered a legacy API which does not support the newest models
    llm = langchain_anthropic.ChatAnthropic(
        max_tokens=100,
        model_name=MODEL_FOR_TESTS_FULL,
        name="custom-anthropic-llm-name",
        streaming=True,
        stream_usage=True,
    )

    template = (
        "Given the title of play, write a short synopsys for that. Title: {title}."
    )

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    for _ in synopsis_chain.stream(
        input=test_prompts, config={"callbacks": [callback]}
    ):
        pass

    callback.flush()

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
                source="sdk",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="custom-anthropic-llm-name",
                input={
                    "messages": [
                        [
                            ANY_DICT.containing(
                                {
                                    "content": "Given the title of play, write a short synopsys for that. Title: Documentary about Bigfoot in Paris.",
                                    "type": "human",
                                }
                            ),
                        ]
                    ]
                },
                output=ANY_BUT_NONE,
                metadata=ANY_DICT.containing(
                    {"created_from": "langchain", "usage": ANY_DICT}
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_USAGE_ANTHROPIC,
                provider="anthropic",
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
