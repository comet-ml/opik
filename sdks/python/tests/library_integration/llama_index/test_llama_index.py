import os
import shutil

import pytest
from llama_index.core import Settings
from llama_index.core.callbacks import CallbackManager

from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.llama_index import LlamaIndexCallbackHandler

from ...testlib import ANY_BUT_NONE, TraceModel, assert_equal


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
