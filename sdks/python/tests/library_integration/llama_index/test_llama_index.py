import os
import shutil

import pytest
from llama_index.core import Settings
from llama_index.core.callbacks import CallbackManager

from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.types import LLMProvider
from opik.integrations.llama_index import LlamaIndexCallbackHandler
from ...testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    ANY_DICT,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
    SpanModel,
)

EXPECTED_OPENAI_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens": ANY_BUT_NONE,
    "original_usage.total_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.accepted_prediction_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.audio_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.reasoning_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens_details.rejected_prediction_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens_details.audio_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens_details.cached_tokens": ANY_BUT_NONE,
}


@pytest.fixture
def index_documents_directory():
    directory_name = "./data/paul_graham/"

    TEXT = "Before college the two main things I worked on, outside of school, were writing and programming."
    os.makedirs(directory_name, exist_ok=True)
    try:
        with open("./data/paul_graham/paul_graham_essay.txt", "wt") as f:
            f.write(TEXT)

        yield directory_name
    finally:
        shutil.rmtree(directory_name, ignore_errors=True)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__happyflow(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager

    # This comment refers to Lama Index Core version 0.11.18.
    # Since `Settings` is a Singleton, we need to manually reset the `Transformations` because
    # they might include objects that still refer to the old Callback Manager instead of the current one (above).
    #
    # The error looks like this: when running this parameterized test without this statement, the second test will
    # always fail, no matter what parameters are passed (even if they are the same).
    Settings.transformations = None

    from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

    documents = SimpleDirectoryReader(index_documents_directory).load_data()
    index = VectorStoreIndex.from_documents(documents)

    query_engine = index.as_query_engine()
    _ = query_engine.query("What did the author do growing up?")

    opik_callback_handler.flush()

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="index_construction",
            input={"documents": ANY_BUT_NONE},
            output=ANY_BUT_NONE,
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=ANY_BUT_NONE,  # too complex spans tree, no check
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="query",
            input={"query_str": "What did the author do growing up?"},
            output=ANY_BUT_NONE,
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=ANY_BUT_NONE,  # too complex spans tree, no check
        ),
    ]

    assert len(fake_backend.trace_trees) == 2
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)

    # check token usage info (now one level less deep due to removed duplicate span)
    llm_response = fake_backend.trace_trees[1].spans[1].spans[3].usage
    assert_dict_has_keys(
        llm_response, ["completion_tokens", "prompt_tokens", "total_tokens"]
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__no_index_construction_logging_happyflow(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    opik_callback_handler = LlamaIndexCallbackHandler(
        project_name=project_name,
        skip_index_construction_trace=True,
    )
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager

    # This comment refers to Lama Index Core version 0.11.18.
    # Since `Settings` is a Singleton, we need to manually reset the `Transformations` because
    # they might include objects that still refer to the old Callback Manager instead of the current one (above).
    #
    # The error looks like this: when running this parameterized test without this statement, the second test will
    # always fail, no matter what parameters are passed (even if they are the same).
    Settings.transformations = None

    from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

    documents = SimpleDirectoryReader(index_documents_directory).load_data()
    index = VectorStoreIndex.from_documents(documents)

    query_engine = index.as_query_engine()
    _ = query_engine.query("What did the author do growing up?")

    opik_callback_handler.flush()

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="query",
            input={"query_str": "What did the author do growing up?"},
            output=ANY_BUT_NONE,
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=ANY_BUT_NONE,  # too complex spans tree, no check
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)

    # check token usage info (now one level less deep due to removed duplicate span)
    llm_response = fake_backend.trace_trees[0].spans[1].spans[3].usage
    assert_dict_has_keys(
        llm_response, ["completion_tokens", "prompt_tokens", "total_tokens"]
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index_chat__happyflow(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager

    # This comment refers to Lama Index Core version 0.11.18.
    # Since `Settings` is a Singleton, we need to manually reset the `Transformations` because
    # they might include objects that still refer to the old Callback Manager instead of the current one (above).
    #
    # The error looks like this: when running this parameterized test without this statement, the second test will
    # always fail, no matter what parameters are passed (even if they are the same).
    Settings.transformations = None

    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    llm = OpenAI(model="gpt-3.5-turbo")
    messages = [
        ChatMessage(
            role="system", content="You are a pirate with a colorful personality."
        ),
        ChatMessage(
            role="user", content="What is your name? Answer with a single word"
        ),
    ]

    _ = llm.chat(messages)

    opik_callback_handler.flush()

    # This is ideal expected output but in tests, the output is still using
    # LlamaIndex types
    # EXPECTED_OUTPUT = {"output": {"role": "assistant", "blocks": [{"block_type": "text", "text": resp}]}}

    expected_messages = [message.model_dump() for message in messages]

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="chat",
            input={"messages": expected_messages},
            output={"output": ANY_BUT_NONE},
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                    usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    spans=[],
                    model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index_stream_chat__happyflow(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager

    # This comment refers to Lama Index Core version 0.11.18.
    # Since `Settings` is a Singleton, we need to manually reset the `Transformations` because
    # they might include objects that still refer to the old Callback Manager instead of the current one (above).
    #
    # The error looks like this: when running this parameterized test without this statement, the second test will
    # always fail, no matter what parameters are passed (even if they are the same).
    Settings.transformations = None

    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    # Configure OpenAI LLM with stream_options to include usage information
    llm = OpenAI(
        model="gpt-3.5-turbo",
        additional_kwargs={"stream_options": {"include_usage": True}},
    )
    messages = [
        ChatMessage(
            role="system", content="You are a pirate with a colorful personality."
        ),
        ChatMessage(
            role="user", content="What is your name? Answer with a single word"
        ),
    ]

    final_resp = ""
    resp = llm.stream_chat(messages)
    for r in resp:
        print(r.delta, end="")
        final_resp += r.delta

    opik_callback_handler.flush()

    # This is ideal expected output but in tests, the output is still using
    # LlamaIndex types
    # EXPECTED_OUTPUT = {"output": {"role": "assistant", "blocks": [{"block_type": "text", "text": final_resp}]}}

    expected_messages = [message.model_dump() for message in messages]

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="chat",
            input={"messages": expected_messages},
            output=None,
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                    usage=ANY_BUT_NONE,  # Usage is now tracked with stream_options
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    spans=[],
                    model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
@pytest.mark.asyncio
async def test_llama_index_async_chat__happyflow(
    ensure_openai_configured,
    fake_backend,
    project_name,
    expected_project_name,
):
    """Test async chat operation creates correct trace and spans"""
    opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager
    Settings.transformations = None

    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    llm = OpenAI(model="gpt-3.5-turbo")
    messages = [
        ChatMessage(
            role="system", content="You are a pirate with a colorful personality."
        ),
        ChatMessage(
            role="user", content="What is your name? Answer with a single word"
        ),
    ]

    _ = await llm.achat(messages)

    opik_callback_handler.flush()

    expected_messages = [message.model_dump() for message in messages]

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="chat",
            input={"messages": expected_messages},
            output={"output": ANY_BUT_NONE},
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                    usage=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    spans=[],
                    model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
@pytest.mark.asyncio
async def test_llama_index_async_stream_chat__happyflow(
    ensure_openai_configured,
    fake_backend,
    project_name,
    expected_project_name,
):
    """Test async streaming chat operation creates correct trace and spans"""
    opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager
    Settings.transformations = None

    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    # Configure OpenAI LLM with stream_options to include usage information
    llm = OpenAI(
        model="gpt-3.5-turbo",
        additional_kwargs={"stream_options": {"include_usage": True}},
    )
    messages = [
        ChatMessage(
            role="system", content="You are a pirate with a colorful personality."
        ),
        ChatMessage(
            role="user", content="What is your name? Answer with a single word"
        ),
    ]

    final_resp = ""
    resp = await llm.astream_chat(messages)
    async for r in resp:
        final_resp += r.delta

    opik_callback_handler.flush()

    expected_messages = [message.model_dump() for message in messages]

    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="chat",
            input={"messages": expected_messages},
            output=None,
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                    usage=ANY_BUT_NONE,  # Usage is now tracked with stream_options
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    spans=[],
                    model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__used_inside_tracked_function__attached_to_existing_trace(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    """Test that LlamaIndex callback respects existing trace context from @opik.track decorator"""
    import opik
    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    @opik.track(project_name=project_name)
    def process_with_llama_index():
        opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
        opik_callback_manager = CallbackManager([opik_callback_handler])
        Settings.callback_manager = opik_callback_manager
        Settings.transformations = None

        llm = OpenAI(model="gpt-3.5-turbo")
        messages = [
            ChatMessage(
                role="system", content="You are a pirate with a colorful personality."
            ),
            ChatMessage(
                role="user", content="What is your name? Answer with a single word"
            ),
        ]

        resp = llm.chat(messages)
        opik_callback_handler.flush()
        return resp

    _ = process_with_llama_index()

    opik.flush_tracker()

    messages = [
        ChatMessage(
            role="system", content="You are a pirate with a colorful personality."
        ),
        ChatMessage(
            role="user", content="What is your name? Answer with a single word"
        ),
    ]
    expected_messages = [message.model_dump() for message in messages]

    # Should have 1 trace (from @opik.track), with the LlamaIndex spans nested inside
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="process_with_llama_index",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        tags=None,
        metadata=None,  # @opik.track doesn't set metadata
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="process_with_llama_index",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                tags=None,
                metadata=None,  # @opik.track doesn't set metadata
                type="general",
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    # LlamaIndex wrapper span (created because external trace exists)
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="chat",
                        input={"messages": expected_messages},
                        output={"output": ANY_BUT_NONE},
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[
                            # LLM span nested inside the wrapper span
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="llm",
                                name="llm",
                                input={"messages": expected_messages},
                                output={"output": ANY_BUT_NONE},
                                tags=None,
                                metadata=ANY_DICT.containing(
                                    {"created_from": "llama_index"}
                                ),
                                usage=ANY_BUT_NONE,  # Usage populated for non-streaming calls
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=expected_project_name,
                                spans=[],
                                model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                                provider=LLMProvider.OPENAI,
                            )
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    # Verify LlamaIndex spans are nested inside the tracked function span
    tracked_function_span = fake_backend.trace_trees[0].spans[0]
    assert len(tracked_function_span.spans) >= 1  # At least one LlamaIndex span exists


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__used_inside_tracked_function_with_existing_span__attached_to_existing_span(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    """Test that LlamaIndex callback respects existing span context"""
    import opik
    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    @opik.track(project_name=project_name)
    def outer_function():
        @opik.track(name="inner_span", project_name=project_name)
        def inner_function():
            opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
            opik_callback_manager = CallbackManager([opik_callback_handler])
            Settings.callback_manager = opik_callback_manager
            Settings.transformations = None

            llm = OpenAI(model="gpt-3.5-turbo")
            messages = [
                ChatMessage(role="user", content="Say hello"),
            ]

            resp = llm.chat(messages)
            opik_callback_handler.flush()
            return resp

        return inner_function()

    _ = outer_function()

    opik.flush_tracker()

    messages = [
        ChatMessage(role="user", content="Say hello"),
    ]
    expected_messages = [message.model_dump() for message in messages]

    # Should have 1 trace with nested structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="outer_function",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        tags=None,
        metadata=None,  # @opik.track doesn't set metadata
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="outer_function",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                tags=None,
                metadata=None,  # @opik.track doesn't set metadata
                type="general",
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="inner_span",
                        input=ANY_BUT_NONE,
                        output=ANY_BUT_NONE,
                        tags=None,
                        metadata=None,  # @opik.track doesn't set metadata
                        type="general",
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[
                            # LlamaIndex wrapper span (created because external trace exists)
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="general",
                                name="chat",
                                input={"messages": expected_messages},
                                output={"output": ANY_BUT_NONE},
                                tags=None,
                                metadata=ANY_DICT.containing(
                                    {"created_from": "llama_index"}
                                ),
                                usage=None,
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=expected_project_name,
                                spans=[
                                    # LLM span nested inside the wrapper span
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        type="llm",
                                        name="llm",
                                        input={"messages": expected_messages},
                                        output={"output": ANY_BUT_NONE},
                                        tags=None,
                                        metadata=ANY_DICT.containing(
                                            {"created_from": "llama_index"}
                                        ),
                                        usage=ANY_BUT_NONE,  # Usage populated for non-streaming calls
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        project_name=expected_project_name,
                                        spans=[],
                                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                                        provider=LLMProvider.OPENAI,
                                    )
                                ],
                            )
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_llama_index__callback_reused_multiple_times__creates_separate_traces(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
):
    """Test that the same callback handler can be reused and creates separate traces"""
    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    opik_callback_handler = LlamaIndexCallbackHandler(
        project_name="llama-index-integration-test"
    )
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager
    Settings.transformations = None

    llm = OpenAI(model="gpt-3.5-turbo")

    # First call
    messages1 = [ChatMessage(role="user", content="Say one")]
    _ = llm.chat(messages1)

    # Second call
    messages2 = [ChatMessage(role="user", content="Say two")]
    _ = llm.chat(messages2)

    opik_callback_handler.flush()

    expected_messages1 = [message.model_dump() for message in messages1]
    expected_messages2 = [message.model_dump() for message in messages2]

    # Should have 2 separate traces
    EXPECTED_TRACE_TREES = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="chat",
            input={"messages": expected_messages1},
            output=ANY_BUT_NONE,  # Chat calls have output
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name="llama-index-integration-test",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages1},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                    usage=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="llama-index-integration-test",
                    spans=[],
                    model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="chat",
            input={"messages": expected_messages2},
            output=ANY_BUT_NONE,  # Chat calls have output
            metadata=ANY_DICT.containing({"created_from": "llama_index"}),
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name="llama-index-integration-test",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages2},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                    usage=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="llama-index-integration-test",
                    spans=[],
                    model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 2
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__used_with_start_as_current_trace__attached_to_external_trace(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    """Test that LlamaIndex callback respects external trace created with start_as_current_trace()"""
    import opik
    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    # Create external trace using start_as_current_trace context manager
    with opik.start_as_current_trace(
        name="external_trace",
        project_name=project_name,
    ):
        opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
        opik_callback_manager = CallbackManager([opik_callback_handler])
        Settings.callback_manager = opik_callback_manager
        Settings.transformations = None

        llm = OpenAI(model="gpt-3.5-turbo")
        messages = [
            ChatMessage(role="user", content="Say hello"),
        ]

        _ = llm.chat(messages)
        opik_callback_handler.flush()

    opik.flush_tracker()

    expected_messages = [message.model_dump() for message in messages]

    # Should have 1 trace (the external one) with LlamaIndex wrapper span and LLM span
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="external_trace",
        input=None,  # External trace doesn't capture input automatically
        output=None,  # External trace doesn't capture output automatically
        tags=None,
        metadata=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            # LlamaIndex wrapper span (created because external trace exists)
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="chat",
                input={"messages": expected_messages},
                output={"output": ANY_BUT_NONE},
                tags=None,
                metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    # LLM span nested inside the wrapper span
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="llm",
                        input={"messages": expected_messages},
                        output={"output": ANY_BUT_NONE},
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                        provider=LLMProvider.OPENAI,
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
@pytest.mark.asyncio
async def test_llama_index_async__used_with_start_as_current_trace__attached_to_external_trace(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    """Test that async LlamaIndex operations respect external trace created with start_as_current_trace()"""
    import opik
    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    # Create external trace using start_as_current_trace context manager
    with opik.start_as_current_trace(
        name="async_external_trace",
        project_name=project_name,
    ):
        opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
        opik_callback_manager = CallbackManager([opik_callback_handler])
        Settings.callback_manager = opik_callback_manager
        Settings.transformations = None

        llm = OpenAI(model="gpt-3.5-turbo")
        messages = [
            ChatMessage(role="user", content="Say hello in 3 words"),
        ]

        _ = await llm.achat(messages)
        opik_callback_handler.flush()

    opik.flush_tracker()

    expected_messages = [message.model_dump() for message in messages]

    # Should have 1 trace (the external one) with LlamaIndex wrapper span and LLM span
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_external_trace",
        input=None,  # External trace doesn't capture input automatically
        output=None,  # External trace doesn't capture output automatically
        tags=None,
        metadata=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            # LlamaIndex wrapper span (created because external trace exists)
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="chat",
                input={"messages": expected_messages},
                output={"output": ANY_BUT_NONE},
                tags=None,
                metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    # LLM span nested inside the wrapper span
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="llm",
                        input={"messages": expected_messages},
                        output={"output": ANY_BUT_NONE},
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                        provider=LLMProvider.OPENAI,
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__query_engine_with_complex_spans__creates_embedding_retrieval_and_llm_spans(
    ensure_openai_configured,
    fake_backend,
    index_documents_directory,
    project_name,
    expected_project_name,
):
    """Test that LlamaIndex query engine creates embedding, retrieval, and LLM spans"""
    from llama_index.core import VectorStoreIndex, SimpleDirectoryReader
    from llama_index.llms.openai import OpenAI
    from llama_index.embeddings.openai import OpenAIEmbedding

    opik_callback_handler = LlamaIndexCallbackHandler(
        project_name=project_name,
        skip_index_construction_trace=True,  # Skip index construction to focus on query trace
    )
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager

    # Use OpenAI for both LLM and embeddings
    Settings.llm = OpenAI(model="gpt-3.5-turbo")
    Settings.embed_model = OpenAIEmbedding(model="text-embedding-3-small")

    # Load documents and create index
    documents = SimpleDirectoryReader(index_documents_directory).load_data()
    index = VectorStoreIndex.from_documents(documents)

    # Create query engine and query it
    query_engine = index.as_query_engine()
    _ = query_engine.query("What did the author do growing up?")

    opik_callback_handler.flush()

    # Should have 1 trace for the query operation with complex span structure
    # LlamaIndex creates a hierarchy: query -> retrieve (with embedding) + synthesize (with chunking, templating, llm)
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="query",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            # Retrieve span with nested embedding
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="retrieve",
                input=ANY_BUT_NONE,  # Retrieve has query_str input
                output=ANY_BUT_NONE,
                tags=None,
                metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="embedding",
                        input=None,
                        output=ANY_BUT_NONE,
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                    )
                ],
            ),
            # Synthesize span with nested processing spans
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="synthesize",
                input=ANY_BUT_NONE,  # Synthesize has query input
                output=ANY_BUT_NONE,
                tags=None,
                metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    # Chunking span
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="chunking",
                        input=ANY_BUT_NONE,
                        output=ANY_BUT_NONE,
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                    ),
                    # Another chunking span
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="chunking",
                        input=ANY_BUT_NONE,
                        output=ANY_BUT_NONE,
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                    ),
                    # Templating span
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="templating",
                        input=ANY_BUT_NONE,
                        output=None,  # Templating span may not have output
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                    ),
                    # LLM span
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="llm",
                        input=ANY_BUT_NONE,
                        output=ANY_BUT_NONE,
                        tags=None,
                        metadata=ANY_DICT.containing({"created_from": "llama_index"}),
                        usage=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                        provider=LLMProvider.OPENAI,
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("llama-index-integration-test", "llama-index-integration-test"),
    ],
)
def test_llama_index__concurrent_pipelines__thread_safe(
    ensure_openai_configured,
    fake_backend,
    project_name,
    expected_project_name,
):
    """Test that callback handler is thread-safe when reused by concurrent pipelines"""
    import concurrent.futures
    from llama_index.llms.openai import OpenAI
    from llama_index.core.llms import ChatMessage

    # Single shared callback handler
    opik_callback_handler = LlamaIndexCallbackHandler(project_name=project_name)
    opik_callback_manager = CallbackManager([opik_callback_handler])
    Settings.callback_manager = opik_callback_manager
    Settings.transformations = None

    def run_pipeline(pipeline_id: int):
        """Run a LlamaIndex pipeline with unique messages"""
        llm = OpenAI(model="gpt-3.5-turbo")
        messages = [
            ChatMessage(role="user", content=f"Say pipeline {pipeline_id} in one word"),
        ]
        response = llm.chat(messages)
        return pipeline_id, response.message.content

    # Run 3 pipelines concurrently
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(run_pipeline, i) for i in range(3)]
        _ = [f.result() for f in concurrent.futures.as_completed(futures)]

    opik_callback_handler.flush()

    # Should have 3 separate traces, one for each pipeline
    assert len(fake_backend.trace_trees) == 3

    # Each trace should be properly formed with its own spans
    for trace_tree in fake_backend.trace_trees:
        assert trace_tree.name == "chat"
        assert trace_tree.project_name == expected_project_name
        assert len(trace_tree.spans) == 1  # One LLM span
        assert trace_tree.spans[0].name == "llm"
        assert trace_tree.spans[0].type == "llm"

    # Verify all traces have different IDs (no collision)
    trace_ids = {tree.id for tree in fake_backend.trace_trees}
    assert len(trace_ids) == 3
