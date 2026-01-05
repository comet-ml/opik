from __future__ import annotations

from typing import Tuple
import uuid

import pytest

import opik
from opik import synchronization
from opik.types import BatchFeedbackScoreDict
from . import verifiers


@pytest.fixture
def active_threads_and_project(
    opik_client: opik.Opik, temporary_project_name: str
) -> Tuple[list[str], str]:
    thread_ids = [str(uuid.uuid4())[-6:], str(uuid.uuid4())[-6:]]

    for thread_id in thread_ids:
        for i in range(5):
            opik_client.trace(
                name=f"trace-name-{i}:{thread_id}",
                input={"input": f"trace-input-{i}:{thread_id}"},
                output={"output": f"trace-output-{i}:{thread_id}"},
                project_name=temporary_project_name,
                thread_id=thread_id,
            )

    opik_client.flush()

    return thread_ids, temporary_project_name


def test_threads_client__search_threads__happy_path(
    opik_client: opik.Opik, active_threads_and_project: Tuple[list[str], str]
):
    active_threads, temporary_project_name = active_threads_and_project
    threads_client = opik_client.get_threads_client()

    # Search for the first thread by ID filter
    filter_string = f'id = "{active_threads[0]}"'
    threads = threads_client.search_threads(
        project_name=temporary_project_name, filter_string=filter_string
    )
    assert len(threads) == 1
    assert threads[0].id == active_threads[0]

    # Search for all threads with active status
    filter_string = 'status = "active"'
    threads = threads_client.search_threads(
        project_name=temporary_project_name, filter_string=filter_string
    )
    assert len(threads) == 2
    for thread in threads:
        assert thread.id in active_threads


def test_threads_client__log_threads_feedback_scores__happy_path(
    opik_client: opik.Opik, active_threads_and_project: Tuple[list[str], str]
):
    active_threads, temporary_project_name = active_threads_and_project
    threads_client = opik_client.get_threads_client()

    # close threads before logging scores - otherwise backend will return 409 error
    for thread_id in active_threads:
        opik_client.rest_client.traces.close_trace_thread(
            project_name=temporary_project_name, thread_id=thread_id
        )

    def check_threads_closed() -> bool:
        threads = threads_client.search_threads(
            project_name=temporary_project_name, filter_string='status = "active"'
        )
        return len(threads) == 0

    # wait for closed threads to propagate
    synchronization.wait_for_done(lambda: check_threads_closed(), timeout=30)

    # log feedback scores
    scores = [
        BatchFeedbackScoreDict(
            id=active_threads[0],
            name="thread-metric-1",
            value=0.75,
            category_name="category-3",
            reason="some-reason-3",
        ),
        BatchFeedbackScoreDict(
            id=active_threads[1],
            name="thread-metric-2",
            value=0.25,
            category_name="category-4",
            reason="some-reason-4",
        ),
    ]
    threads_client.log_threads_feedback_scores(
        scores=scores, project_name=temporary_project_name
    )

    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=active_threads[0],
        project_name=temporary_project_name,
        feedback_scores=[scores[0]],
    )

    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=active_threads[1],
        project_name=temporary_project_name,
        feedback_scores=[scores[1]],
    )


def test_threads_client__close_thread__happy_path(
    opik_client: opik.Opik, temporary_project_name
):
    threads_client = opik_client.get_threads_client()

    thread_id = str(uuid.uuid4())[-6:]
    opik_client.trace(
        name="some-trace-name",
        thread_id=thread_id,
        project_name=temporary_project_name,
    )

    opik_client.flush()

    threads = threads_client.search_threads(
        project_name=temporary_project_name,
        filter_string='status = "active"',
    )
    assert len(threads) == 1
    assert threads[0].id == thread_id

    threads_client.close_thread(
        thread_id=thread_id,
        project_name=temporary_project_name,
    )

    threads = threads_client.search_threads(
        project_name=temporary_project_name,
        filter_string='status = "active"',
    )
    assert len(threads) == 0


def test_threads_client__search_threads__filter_by_feedback_score(
    opik_client: opik.Opik, temporary_project_name
):
    # Create a unique metric name to avoid conflicts with other tests
    unique_metric = f"test_metric_{str(uuid.uuid4()).replace('-', '_')[-8:]}"
    threads_client = opik_client.get_threads_client()

    # Create thread with feedback score
    thread_with_score = str(uuid.uuid4())[-6:]
    opik_client.trace(
        name="trace-with-score",
        thread_id=thread_with_score,
        project_name=temporary_project_name,
    )

    # Create thread without feedback score
    thread_without_score = str(uuid.uuid4())[-6:]
    opik_client.trace(
        name="trace-without-score",
        thread_id=thread_without_score,
        project_name=temporary_project_name,
    )

    opik_client.flush()

    # Close threads before logging scores
    for thread_id in [thread_with_score, thread_without_score]:
        opik_client.rest_client.traces.close_trace_thread(
            project_name=temporary_project_name, thread_id=thread_id
        )

    def check_threads_closed() -> bool:
        threads = threads_client.search_threads(
            project_name=temporary_project_name, filter_string='status = "active"'
        )
        return len(threads) == 0

    synchronization.wait_for_done(lambda: check_threads_closed(), timeout=30)

    # Log feedback score to one thread
    threads_client.log_threads_feedback_scores(
        scores=[
            BatchFeedbackScoreDict(
                id=thread_with_score,
                name=unique_metric,
                value=0.85,
                category_name="test-category",
                reason="test-reason",
            )
        ],
        project_name=temporary_project_name,
    )

    # Wait for feedback scores to propagate
    def check_feedback_score_logged() -> bool:
        threads = threads_client.search_threads(
            project_name=temporary_project_name,
            filter_string=f"feedback_scores.{unique_metric} is_not_empty",
        )
        return len(threads) == 1

    synchronization.wait_for_done(lambda: check_feedback_score_logged(), timeout=30)

    # Test filtering with is_not_empty
    threads_not_empty = threads_client.search_threads(
        project_name=temporary_project_name,
        filter_string=f"feedback_scores.{unique_metric} is_not_empty",
    )
    thread_ids_not_empty = {thread.id for thread in threads_not_empty}
    assert (
        thread_with_score in thread_ids_not_empty
    ), "Thread with score should be found with is_not_empty filter"
    assert (
        thread_without_score not in thread_ids_not_empty
    ), "Thread without score should not be found with is_not_empty filter"

    # Test filtering with = operator
    threads_with_value = threads_client.search_threads(
        project_name=temporary_project_name,
        filter_string=f"feedback_scores.{unique_metric} = 0.85",
    )
    thread_ids_with_value = {thread.id for thread in threads_with_value}
    assert (
        thread_with_score in thread_ids_with_value
    ), "Thread with score value 0.85 should be found"
    assert (
        thread_without_score not in thread_ids_with_value
    ), "Thread without score should not be found"

    # Verify is_not_empty and = return the same thread
    assert (
        thread_ids_not_empty == thread_ids_with_value
    ), "is_not_empty and = filters should return the same threads for this test case"
