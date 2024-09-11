import mock
import os
import shutil
from opik.message_processing import streamer_constructors
from ...testlib import backend_emulator_message_processor
from ...testlib import (
    TraceModel,
    ANY_BUT_NONE,
    assert_equal,
)
import pytest
import requests

from llama_index.core import Settings
from llama_index.core.callbacks import CallbackManager
from opik.integrations.llama_index import LlamaIndexCallbackHandler


@pytest.fixture()
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")


@pytest.fixture
def index_documents_directory():
    directory_name = "./data/paul_graham/"

    os.makedirs(directory_name, exist_ok=True)
    try:
        url = "https://raw.githubusercontent.com/run-llama/llama_index/main/docs/docs/examples/data/paul_graham/paul_graham_essay.txt"
        response = requests.get(url)
        with open("./data/paul_graham/paul_graham_essay.txt", "wb") as f:
            f.write(response.content)

        yield directory_name
    finally:
        shutil.rmtree(directory_name, ignore_errors=True)


def test_llama_index__happyflow(
    ensure_openai_configured,
    fake_streamer,
    index_documents_directory,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        opik_callback_handler = LlamaIndexCallbackHandler()
        Settings.callback_manager = CallbackManager([opik_callback_handler])

        from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

        documents = SimpleDirectoryReader(index_documents_directory).load_data()
        index = VectorStoreIndex.from_documents(documents)
        query_engine = index.as_query_engine()

        response = query_engine.query("What did the author do growing up?")
        print(response)

        opik_callback_handler.flush()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREES = [
            TraceModel(
                id=ANY_BUT_NONE,
                name="index_construction",
                input={"documents": ANY_BUT_NONE},
                output=ANY_BUT_NONE,
                metadata={"created_from": "llama_index"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
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
                spans=ANY_BUT_NONE,  # too complex spans tree, no check
            ),
        ]

        assert len(fake_message_processor_.trace_trees) == 2
        assert_equal(EXPECTED_TRACE_TREES, fake_message_processor_.trace_trees)
