import os
import random
import string
import tempfile
import warnings
from typing import cast

import numpy as np

from unittest import mock
import pytest

from opik import context_storage
from opik.api_objects import opik_client
from opik.healthcheck import connection_monitor, connection_probe
from opik.message_processing import streamer_constructors
from opik.message_processing.replay import replay_manager

from . import testlib
from .testlib import (
    backend_emulator_message_processor,
    environment,
    noop_file_upload_manager,
)


@pytest.fixture(autouse=True)
def clear_context_storage():
    yield
    context_storage.clear_all()


@pytest.fixture(autouse=True)
def shutdown_cached_client_after_test():
    yield
    if opik_client.get_client_cached.cache_info().currsize > 0:
        opik_client.get_client_cached().end()
        opik_client.get_client_cached.cache_clear()


@pytest.fixture
def fake_file_upload_manager():
    upload_manager_emulator = noop_file_upload_manager.FileUploadManagerEmulator()
    yield upload_manager_emulator


@pytest.fixture
def fake_replay_manager():
    probe = mock.Mock(spec=connection_probe.ConnectionProbe)
    probe.check_connection.return_value = connection_probe.ProbeResult(True, None)

    monitor = connection_monitor.OpikConnectionMonitor(
        ping_interval=10,
        check_timeout=1,
        probe=probe,
    )

    fallback_replay = replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=10,
        batch_replay_delay=0.5,
        tick_interval_seconds=5.0,
    )
    return fallback_replay


@pytest.fixture
def patch_streamer(
    fake_file_upload_manager: noop_file_upload_manager.FileUploadManagerEmulator,
    fake_replay_manager: replay_manager.ReplayManager,
):
    streamer = None
    try:
        # Pass the upload manager to the emulator so it can access attachments
        fake_message_processor_ = (
            backend_emulator_message_processor.BackendEmulatorMessageProcessor(
                file_upload_manager=fake_file_upload_manager
            )
        )
        streamer = streamer_constructors.construct_streamer(
            message_processor=fake_message_processor_,
            n_consumers=1,
            use_batching=True,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            use_attachment_extraction=False,
            fallback_replay_manager=fake_replay_manager,
        )

        yield streamer, fake_message_processor_
    finally:
        if streamer is not None:
            streamer.close(timeout=5)


@pytest.fixture
def patch_streamer_without_batching(
    fake_replay_manager: replay_manager.ReplayManager,
):
    streamer = None
    try:
        # Create an upload manager first
        fake_upload_manager = noop_file_upload_manager.FileUploadManagerEmulator()
        # Pass the upload manager to the emulator so it can access attachments
        fake_message_processor_ = (
            backend_emulator_message_processor.BackendEmulatorMessageProcessor(
                file_upload_manager=fake_upload_manager
            )
        )
        streamer = streamer_constructors.construct_streamer(
            message_processor=fake_message_processor_,
            n_consumers=1,
            use_batching=False,
            file_uploader=fake_upload_manager,
            max_queue_size=None,
            use_attachment_extraction=False,
            fallback_replay_manager=fake_replay_manager,
        )

        yield streamer, fake_message_processor_
    finally:
        if streamer is not None:
            streamer.close(timeout=5)


@pytest.fixture
def fake_backend(patch_streamer):
    """
    Patches the function that creates an instance of Streamer under the hood of Opik.
    As a result, instead of sending data to the backend, it's being passed to
    the backend emulator, which uses this data to build span and trace trees.

    The resulting trees can be accessed via `fake_backend.trace_trees` or
    `fake_backend.span_trees` and then used for comparing with expected span/trace trees.

    The trees are built via special classes stored in testlib.models.
    """
    streamer, fake_message_processor_ = patch_streamer

    fake_message_processor_ = cast(
        backend_emulator_message_processor.BackendEmulatorMessageProcessor,
        fake_message_processor_,
    )

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        yield fake_message_processor_


@pytest.fixture
def fake_backend_without_batching(patch_streamer_without_batching):
    """
    Same as fake_backend but must be used when batching is not supported
    (e.g., when there are Span/Trace update requests)
    """
    streamer, fake_message_processor_ = patch_streamer_without_batching

    fake_message_processor_ = cast(
        backend_emulator_message_processor.BackendEmulatorMessageProcessor,
        fake_message_processor_,
    )

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        yield fake_message_processor_


@pytest.fixture
def fake_backend_with_patched_environment(request, patch_streamer):
    """
    Allows patching environment variables for the duration of the test.
    Creates a fake backend as in the `fake_backend` fixture.
    """
    with testlib.patch_environ(add_keys=request.param):
        streamer, fake_message_processor_ = patch_streamer

        fake_message_processor_ = cast(
            backend_emulator_message_processor.BackendEmulatorMessageProcessor,
            fake_message_processor_,
        )

        mock_construct_online_streamer = mock.Mock()
        mock_construct_online_streamer.return_value = streamer

        with mock.patch.object(
            streamer_constructors,
            "construct_online_streamer",
            mock_construct_online_streamer,
        ):
            yield fake_message_processor_


def random_chars(n: int = 6) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


@pytest.fixture()
def configure_opik_local_env_vars():
    with testlib.patch_environ(
        {
            "OPIK_URL_OVERRIDE": "http://localhost:5173/api",
        }
    ):
        yield


@pytest.fixture()
def configure_opik_not_configured():
    with testlib.patch_environ(
        add_keys={},
        remove_keys=[
            "OPIK_URL_OVERRIDE",
            "OPIK_API_KEY",
            "OPIK_WORKSPACE",
        ],
    ):
        yield


@pytest.fixture()
def temp_file_15kb():
    file_size = 15 * 1024
    with tempfile.NamedTemporaryFile(delete=True) as f:
        f.write(np.random.bytes(file_size))
        f.flush()

        yield f


@pytest.fixture()
def temp_file_15mb():
    file_size = 15 * 1024 * 1024
    with tempfile.NamedTemporaryFile(delete=True) as f:
        f.write(np.random.bytes(file_size))
        f.flush()

        yield f


@pytest.fixture(scope="session")
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables
    if not environment.has_openai_api_key():
        raise Exception(
            "OpenAI not configured! Ensure you have set the OPENAI_API_KEY and OPENAI_ORG_ID environment variables."
        )


@pytest.fixture(scope="session")
def ensure_google_project_and_location_configured():
    if not (
        "GOOGLE_CLOUD_PROJECT" in os.environ and "GOOGLE_CLOUD_LOCATION" in os.environ
    ):
        raise Exception(
            "GOOGLE_CLOUD_PROJECT and GOOGLE_CLOUD_LOCATION env vars must be set!"
        )


@pytest.fixture(scope="session")
def ensure_anthropic_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if "ANTHROPIC_API_KEY" not in os.environ:
        raise Exception("Anthropic not configured!")


@pytest.fixture(scope="session")
def ensure_vertexai_configured(ensure_google_project_and_location_configured):
    GOOGLE_APPLICATION_CREDENTIALS_PATH = "gcp_credentials.json"

    if "GITHUB_ACTIONS" not in os.environ:
        if "GOOGLE_APPLICATION_CREDENTIALS" not in os.environ:
            raise Exception("GOOGLE_APPLICATION_CREDENTIALS env var must be configured")
        yield
        return

    if "GCP_CREDENTIALS_JSON" not in os.environ:
        raise Exception(
            "GCP_CREDENTIALS_JSON env var with credentials json content must be set"
        )

    try:
        gcp_credentials = os.environ["GCP_CREDENTIALS_JSON"]
        with open(GOOGLE_APPLICATION_CREDENTIALS_PATH, mode="wt") as output_file:
            output_file.write(gcp_credentials)

        with testlib.patch_environ(
            add_keys={
                "GOOGLE_APPLICATION_CREDENTIALS": GOOGLE_APPLICATION_CREDENTIALS_PATH
            }
        ):
            yield
    finally:
        if os.path.exists(GOOGLE_APPLICATION_CREDENTIALS_PATH):
            os.remove(GOOGLE_APPLICATION_CREDENTIALS_PATH)


@pytest.fixture(scope="session")
def ensure_google_api_configured():
    GOOGLE_API_KEY = "GOOGLE_API_KEY"
    if GOOGLE_API_KEY not in os.environ:
        raise Exception(f"{GOOGLE_API_KEY} env var must be set")


@pytest.fixture(scope="session")
def ensure_aws_bedrock_configured():
    import boto3

    session = boto3.Session()

    bedrock_client = session.client(service_name="bedrock")
    try:
        available_models = bedrock_client.list_foundation_models()["modelSummaries"]
        if not available_models:
            raise Exception("AWS Bedrock not configured! No models available")
    except Exception as e:
        raise Exception(f"AWS Bedrock not configured! {e}")


@pytest.fixture(scope="session")
def ensure_groq_configured():
    # don't use assertion here to prevent printing os.environ with all env variables
    if "GROQ_API_KEY" not in os.environ:
        raise Exception("Groq is not configured!")


warnings.filterwarnings(
    "ignore",
    message=".*PydanticDeprecatedSince20.*",
    category=DeprecationWarning,
)
warnings.filterwarnings(
    "ignore",
    message=".*0 counts of 2-gram overlaps.*",
    category=UserWarning,
)
