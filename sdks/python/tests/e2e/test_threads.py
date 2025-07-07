import uuid

import pytest

from opik import synchronization
from opik.types import FeedbackScoreDict
from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME


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
    threads_client = opik_client.get_threads_client()

    # Search for the first thread by ID filter
    filter_string = f'id = "{active_threads[0]}"'
    threads = threads_client.search_threads(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string=filter_string
    )
    assert len(threads) == 1
    assert threads[0].id == active_threads[0]

    # Search for all threads with active status
    filter_string = 'status = "active"'
    threads = threads_client.search_threads(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string=filter_string
    )
    assert len(threads) == 2
    for thread in threads:
        assert thread.id in active_threads


def test_threads_client__log_threads_feedback_scores__happy_path(
    opik_client, active_threads
):
    threads_client = opik_client.get_threads_client()

    # close threads before logging scores - otherwise backend will return 409 error
    for thread_id in active_threads:
        opik_client.rest_client.traces.close_trace_thread(
            project_name=OPIK_E2E_TESTS_PROJECT_NAME, thread_id=thread_id
        )

    def check_threads_closed() -> bool:
        threads = threads_client.search_threads(
            project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string='status = "active"'
        )
        return len(threads) == 0

    # wait for closed threads to propagate
    synchronization.wait_for_done(lambda: check_threads_closed(), timeout=30)

    # log feedback scores
    scores = [
        FeedbackScoreDict(
            id=active_threads[0],
            name="thread-metric-1",
            value=0.75,
            category_name="category-3",
            reason="some-reason-3",
        ),
        FeedbackScoreDict(
            id=active_threads[1],
            name="thread-metric-2",
            value=0.25,
            category_name="category-4",
            reason="some-reason-4",
        ),
    ]
    threads_client.log_threads_feedback_scores(
        scores=scores, project_name=OPIK_E2E_TESTS_PROJECT_NAME
    )

    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=active_threads[0],
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        feedback_scores=[scores[0]],
    )

    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=active_threads[1],
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        feedback_scores=[scores[1]],
    )
