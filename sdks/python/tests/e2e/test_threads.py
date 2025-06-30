import uuid

import pytest

from .conftest import OPIK_E2E_TESTS_PROJECT_NAME
from opik.api_objects.threads import threads_client


@pytest.fixture
def active_threads(opik_client):
    thread_ids = [str(uuid.uuid4())[-6:], str(uuid.uuid4())[-6:]]

    for thread_id in thread_ids:
        for i in range(5):
            opik_client.trace(
                name=f"trace-name-{i}:{thread_id}",
                input={"input": f"trace-input-{i}:{thread_id}"},
                output={"output": f"trace-output-{i}:{thread_id}"},
                project_name=OPIK_E2E_TESTS_PROJECT_NAME,
                thread_id=thread_id,
            )

    opik_client.flush()

    yield thread_ids

    # close threads
    for thread_id in thread_ids:
        opik_client.rest_client.traces.close_trace_thread(
            project_name=OPIK_E2E_TESTS_PROJECT_NAME, thread_id=thread_id
        )


def test_threads_client__search_threads__happy_path(opik_client, active_threads):
    threads_client_ = threads_client.ThreadsClient(opik_client)

    # Search for the first thread by ID filter
    filter_string = f'id = "{active_threads[0]}"'
    threads = threads_client_.search_threads(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string=filter_string
    )
    assert len(threads) == 1
    assert threads[0].id == active_threads[0]

    # Search for all threads with active status
    filter_string = 'status = "active"'
    threads = threads_client_.search_threads(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string=filter_string
    )
    assert len(threads) == 2
    for thread in threads:
        assert thread.id in active_threads
