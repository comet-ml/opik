import pytest
from langchain_core.prompts import PromptTemplate

from opik.integrations.langchain.opik_tracer import OpikTracer
from opik import jsonable_encoder
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)
from .constants import EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT, BEDROCK_MODEL_FOR_TESTS
import langchain_aws

pytestmark = pytest.mark.usefixtures("ensure_aws_bedrock_configured")

SOME_BEDROCK_CHAT_MODEL_NAME = "custom-bedrock-llm-name"

parametrize_chat_model = pytest.mark.parametrize(
    "chat_model",
    [
        langchain_aws.ChatBedrock(
            model_id=BEDROCK_MODEL_FOR_TESTS,
            name=SOME_BEDROCK_CHAT_MODEL_NAME,
            max_tokens=10,
        ),
        langchain_aws.ChatBedrockConverse(
            model_id=BEDROCK_MODEL_FOR_TESTS,
            name=SOME_BEDROCK_CHAT_MODEL_NAME,
            max_tokens=10,
        ),
    ],
    ids=["ChatBedrock", "ChatBedrockConverse"],
)

parametrize_streaming_chat_model = pytest.mark.parametrize(
    "chat_model",
    [
        langchain_aws.ChatBedrock(
            model_id=BEDROCK_MODEL_FOR_TESTS,
            name=SOME_BEDROCK_CHAT_MODEL_NAME,
            max_tokens=10,
            streaming=True,
        ),
        langchain_aws.ChatBedrockConverse(  # doesn't have streaming parameter
            model_id=BEDROCK_MODEL_FOR_TESTS,
            name=SOME_BEDROCK_CHAT_MODEL_NAME,
            max_tokens=10,
        ),
    ],
    ids=["ChatBedrock", "ChatBedrockConverse"],
)


@parametrize_chat_model
def test_langchain__bedrock_chat_is_used__token_usage_and_provider_is_logged__happyflow(
    fake_backend,
    chat_model,
):
    template = "Given the title of play, write a synopsys for that. Title: {title}."
    prompt_template = PromptTemplate(input_variables=["title"], template=template)
    synopsis_chain = prompt_template | chat_model
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    result = synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})
    result_as_json = jsonable_encoder.encode(result)

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output={"output": result_as_json},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_STRING,
        tags=["tag1", "tag2"],
        metadata={"a": "b", "created_from": "langchain"},
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
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(fake_backend.trace_trees[0], EXPECTED_TRACE_TREE)


@parametrize_streaming_chat_model
def test_langchain__bedrock_chat_is_used__streaming_mode__token_usage_and_provider_are_logged(
    fake_backend,
    chat_model,
):
    template = "Given the title of play, write a synopsys for that. Title: {title}."
    prompt_template = PromptTemplate(input_variables=["title"], template=template)
    synopsis_chain = prompt_template | chat_model
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    chunks = []
    for chunk in synopsis_chain.stream(test_prompts, config={"callbacks": [callback]}):
        chunks.append(chunk)

    callback.flush()

    assert len(chunks) > 0, "Expected to receive streaming chunks"

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
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(fake_backend.trace_trees[0], EXPECTED_TRACE_TREE)


@pytest.mark.asyncio
@parametrize_chat_model
async def test_langchain__bedrock_chat_is_used__async_ainvoke__token_usage_and_provider_are_logged(
    fake_backend,
    chat_model,
):
    """Test async ainvoke with Bedrock"""
    template = "Given the title of play, write a synopsys for that. Title: {title}."
    prompt_template = PromptTemplate(input_variables=["title"], template=template)
    synopsis_chain = prompt_template | chat_model
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    result = await synopsis_chain.ainvoke(
        input=test_prompts, config={"callbacks": [callback]}
    )
    result_as_json = jsonable_encoder.encode(result)

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output={"output": result_as_json},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_STRING,
        tags=["tag1", "tag2"],
        metadata={"a": "b", "created_from": "langchain"},
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
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(fake_backend.trace_trees[0], EXPECTED_TRACE_TREE)


@pytest.mark.asyncio
@parametrize_streaming_chat_model
async def test_langchain__bedrock_chat_is_used__async_astream__token_usage_and_provider_are_logged(
    fake_backend,
    chat_model,
):
    template = "Given the title of play, write a synopsys for that. Title: {title}."
    prompt_template = PromptTemplate(input_variables=["title"], template=template)
    synopsis_chain = prompt_template | chat_model
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    chunks = []
    async for chunk in synopsis_chain.astream(
        test_prompts, config={"callbacks": [callback]}
    ):
        chunks.append(chunk)

    callback.flush()

    assert len(chunks) > 0, "Expected to receive async streaming chunks"

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
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(fake_backend.trace_trees[0], EXPECTED_TRACE_TREE)
