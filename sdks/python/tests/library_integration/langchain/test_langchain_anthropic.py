import langchain_anthropic
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


pytestmark = pytest.mark.usefixtures("ensure_anthropic_configured")


def _assert_usage_validity(usage: Dict[str, Any]):
    REQUIRED_USAGE_KEYS = [
        "completion_tokens",
        "prompt_tokens",
        "total_tokens",
        "original_usage.input_tokens",
        "original_usage.output_tokens",
        "original_usage.cache_creation_input_tokens",
        "original_usage.cache_read_input_tokens",
    ]

    assert_dict_has_keys(usage, REQUIRED_USAGE_KEYS)


def test_langchain__anthropic_chat_is_used__token_usage_and_provider_is_logged__happyflow(
    fake_backend,
):
    # lanchain_anthropic.Anthropic/AnthropicLLM is not tested because it is considered a legacy API which does not support the newest models
    llm = langchain_anthropic.ChatAnthropic(
        max_tokens=100,
        model_name="claude-3-5-sonnet-latest",
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
                        name="custom-anthropic-llm-name",
                        input={
                            "messages": [
                                [
                                    {
                                        "content": "Given the title of play, write a short synopsys for that. Title: Documentary about Bigfoot in Paris.",
                                        "additional_kwargs": {},
                                        "response_metadata": {},
                                        "type": "human",
                                        "name": None,
                                        "id": None,
                                        "example": False,
                                    }
                                ]
                            ]
                        },
                        output=ANY_BUT_NONE,
                        metadata={
                            "batch_size": ANY_BUT_NONE,
                            "invocation_params": ANY_DICT,
                            "metadata": ANY_DICT,
                            "options": ANY_DICT,
                            "usage": ANY_DICT,
                            "created_from": "langchain",
                        },
                        # metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=ANY_DICT,
                        spans=[],
                        provider="anthropic",
                        model=ANY_STRING(startswith="claude-3-5-sonnet"),
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


def test_langchain__anthropic_chat_is_used__streaming_mode__token_usage_and_provider_is_logged__happyflow(
    fake_backend,
):
    # lanchain_anthropic.Anthropic/AnthropicLLM is not tested because it is considered a legacy API which does not support the newest models
    llm = langchain_anthropic.ChatAnthropic(
        max_tokens=100,
        model_name="claude-3-5-sonnet-latest",
        name="custom-anthropic-llm-name",
    )

    template = (
        "Given the title of play, write a short synopsys for that. Title: {title}."
    )

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    for chunk in synopsis_chain.stream(
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
                        name="custom-anthropic-llm-name",
                        input={
                            "messages": [
                                [
                                    {
                                        "content": "Given the title of play, write a short synopsys for that. Title: Documentary about Bigfoot in Paris.",
                                        "additional_kwargs": {},
                                        "response_metadata": {},
                                        "type": "human",
                                        "name": None,
                                        "id": None,
                                        "example": False,
                                    }
                                ]
                            ]
                        },
                        output=ANY_BUT_NONE,
                        metadata={
                            "batch_size": ANY_BUT_NONE,
                            "invocation_params": ANY_DICT,
                            "metadata": ANY_DICT,
                            "options": ANY_DICT,
                            "usage": ANY_DICT,
                            "created_from": "langchain",
                        },
                        # metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=ANY_DICT,
                        spans=[],
                        provider="anthropic",
                        model=ANY_STRING(startswith="claude-3-5-sonnet"),
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
