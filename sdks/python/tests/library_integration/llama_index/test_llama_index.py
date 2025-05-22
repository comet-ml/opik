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
            metadata={"created_from": "llama_index"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=ANY_BUT_NONE,  # too complex spans tree, no check
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="query",
            input={"query_str": "What did the author do growing up?"},
            output=ANY_BUT_NONE,
            metadata={"created_from": "llama_index"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=ANY_BUT_NONE,  # too complex spans tree, no check
        ),
    ]

    assert len(fake_backend.trace_trees) == 2
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)

    # check token usage info
    llm_response = fake_backend.trace_trees[1].spans[0].spans[1].spans[3].usage
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
        # TraceModel(
        #     id=ANY_BUT_NONE,
        #     name="index_construction",
        #     input={"documents": ANY_BUT_NONE},
        #     output=ANY_BUT_NONE,
        #     metadata={"created_from": "llama_index"},
        #     start_time=ANY_BUT_NONE,
        #     end_time=ANY_BUT_NONE,
        #     project_name=expected_project_name,
        #     spans=ANY_BUT_NONE,  # too complex spans tree, no check
        # ),
        TraceModel(
            id=ANY_BUT_NONE,
            name="query",
            input={"query_str": "What did the author do growing up?"},
            output=ANY_BUT_NONE,
            metadata={"created_from": "llama_index"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=ANY_BUT_NONE,  # too complex spans tree, no check
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)

    # check token usage info
    llm_response = fake_backend.trace_trees[0].spans[0].spans[1].spans[3].usage
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
            metadata={"created_from": "llama_index"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=None,
                    usage=EXPECTED_OPENAI_USAGE_LOGGED_FORMAT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    spans=[],
                    model=ANY_STRING(startswith="gpt-3.5-turbo"),
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

    llm = OpenAI(model="gpt-3.5-turbo")
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
            metadata={"created_from": "llama_index"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="llm",
                    input={"messages": expected_messages},
                    output={"output": ANY_BUT_NONE},
                    tags=None,
                    metadata=None,
                    usage=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    spans=[],
                    model=ANY_STRING(startswith="gpt-3.5-turbo"),
                    provider=LLMProvider.OPENAI,
                )
            ],
        ),
    ]

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREES, fake_backend.trace_trees)
