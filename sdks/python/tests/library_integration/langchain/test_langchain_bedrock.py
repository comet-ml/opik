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
from .constants import EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT
import langchain_aws

pytestmark = pytest.mark.usefixtures("ensure_bedrock_configured")


def _assert_usage_validity(usage: Dict[str, Any]):
    REQUIRED_USAGE_KEYS = [
        "completion_tokens",
        "prompt_tokens", 
        "total_tokens",
        "original_usage.inputTokens",
        "original_usage.outputTokens",
    ]

    assert_dict_has_keys(usage, REQUIRED_USAGE_KEYS)


BEDROCK_MODEL_FOR_TESTS = "us.anthropic.claude-sonnet-4-20250514-v1:0"

def test_langchain__bedrock_chat_is_used__token_usage_and_provider_is_logged__happyflow(
    fake_backend,
):
    llm = langchain_aws.ChatBedrock(
        model_id=BEDROCK_MODEL_FOR_TESTS,
        region_name="us-east-1",
        model_kwargs={
            "temperature": 0.7,
            "max_tokens": 10,
        },
        name="custom-bedrock-llm-name",
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
        output={"output": ANY_BUT_NONE},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_STRING,
        tags=["tag1", "tag2"],
        metadata={"a": "b", "created_from": "langchain"},
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={"output": ANY_BUT_NONE},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_STRING,
                metadata={"a": "b", "created_from": "langchain"},
                tags=["tag1", "tag2"],
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="PromptTemplate",
                        type="tool",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=ANY_STRING,
                        metadata={"created_from": "langchain"},
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="custom-bedrock-llm-name",
                        type="llm",
                        input=ANY_BUT_NONE,
                        output=ANY_DICT.containing({"generations": ANY_BUT_NONE}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=ANY_STRING,
                        metadata=ANY_DICT,
                        usage=EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT,
                        spans=[],
                        provider="bedrock",
                        model=BEDROCK_MODEL_FOR_TESTS,
                    ),
                ],
            ),
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(fake_backend.trace_trees[0], EXPECTED_TRACE_TREE)
