from __future__ import annotations

import uuid
import pytest

import opik
from opik import synchronization


@pytest.fixture
def annotation_queue_name():
    return f"e2e-tests-annotation-queue-{str(uuid.uuid4())[-6:]}"


def test_annotation_queue__add_traces__happyflow(
    opik_client: opik.Opik,
    temporary_project_name: str,
    annotation_queue_name: str,
):
    """Test creating an annotation queue and adding traces to it."""
    # Create traces and capture the returned Trace objects
    trace1 = opik_client.trace(
        name="trace-1",
        input={"input": "trace-1-input"},
        output={"output": "trace-1-output"},
        project_name=temporary_project_name,
    )
    trace2 = opik_client.trace(
        name="trace-2",
        input={"input": "trace-2-input"},
        output={"output": "trace-2-output"},
        project_name=temporary_project_name,
    )

    opik_client.flush()

    # Wait for traces to be available (which also ensures project is created)
    def traces_available():
        traces = opik_client.search_traces(
            project_name=temporary_project_name,
            max_results=10,
        )
        return len(traces) >= 2

    synchronization.wait_for_done(traces_available, timeout=30)

    # Create annotation queue after project exists
    queue = opik_client.create_annotation_queue(
        name=annotation_queue_name,
        scope="trace",
        project_name=temporary_project_name,
        description="Test queue for traces",
        instructions="Please review these traces",
    )

    assert queue.id is not None
    assert queue.name == annotation_queue_name
    assert queue.scope == "trace"
    assert queue.description == "Test queue for traces"
    assert queue.instructions == "Please review these traces"

    # Add traces directly using the Trace objects returned by opik_client.trace()
    queue.add_traces([trace1, trace2])

    # Verify queue items count
    def check_items_count():
        updated_queue = opik_client.get_annotation_queue(queue.id)
        return updated_queue.items_count == 2

    synchronization.wait_for_done(check_items_count, timeout=30)

    # Verify we can retrieve the queue
    retrieved_queue = opik_client.get_annotation_queue(queue.id)
    assert retrieved_queue.id == queue.id
    assert retrieved_queue.name == annotation_queue_name
    assert retrieved_queue.items_count == 2

    # Remove one trace from queue
    queue.remove_traces([trace1])

    # Verify queue items count after removal
    def check_items_count_after_removal():
        updated_queue = opik_client.get_annotation_queue(queue.id)
        return updated_queue.items_count == 1

    synchronization.wait_for_done(check_items_count_after_removal, timeout=30)

    # Cleanup: delete the queue
    queue.delete()

    # Verify queue is deleted
    with pytest.raises(opik.exceptions.OpikException):
        opik_client.get_annotation_queue(queue.id)


def test_annotation_queue__add_threads__happyflow(
    opik_client: opik.Opik,
    temporary_project_name: str,
    annotation_queue_name: str,
):
    """Test creating an annotation queue and adding threads to it."""
    # Thread IDs must be valid UUIDs for annotation queue API
    # Use full UUIDs and store them to filter later
    thread_id_1 = "thread_1"
    thread_id_2 = "thread_2"
    thread_ids = {thread_id_1, thread_id_2}

    # Create traces that belong to threads
    for i in range(3):
        opik_client.trace(
            name=f"trace-thread1-{i}",
            input={"input": f"input-{i}"},
            output={"output": f"output-{i}"},
            project_name=temporary_project_name,
            thread_id=thread_id_1,
        )

    for i in range(2):
        opik_client.trace(
            name=f"trace-thread2-{i}",
            input={"input": f"input-{i}"},
            output={"output": f"output-{i}"},
            project_name=temporary_project_name,
            thread_id=thread_id_2,
        )

    opik_client.flush()

    # Wait for threads to be available (which also ensures project is created)
    threads_client = opik_client.get_threads_client()

    def threads_available():
        threads = threads_client.search_threads(
            project_name=temporary_project_name,
            max_results=100,
        )
        found_ids = {t.id for t in threads}
        return thread_ids.issubset(found_ids)

    synchronization.wait_for_done(threads_available, timeout=30)

    # Create annotation queue for threads after project exists
    queue = opik_client.create_annotation_queue(
        name=annotation_queue_name,
        scope="thread",
        project_name=temporary_project_name,
        description="Test queue for threads",
        instructions="Please review these threads",
    )

    assert queue.id is not None
    assert queue.name == annotation_queue_name
    assert queue.scope == "thread"

    # Get threads from API and filter by our thread IDs
    threads = threads_client.search_threads(
        project_name=temporary_project_name,
        filter_string=f'id = "{thread_id_1}" or id = "{thread_id_2}"',
        max_results=10,
    )
    assert len(threads) == 2

    # Add threads to queue
    queue.add_threads(threads)

    # Verify queue items count
    def check_items_count():
        updated_queue = opik_client.get_annotation_queue(queue.id)
        return updated_queue.items_count == 2

    synchronization.wait_for_done(check_items_count, timeout=30)

    # Verify we can retrieve the queue
    retrieved_queue = opik_client.get_annotation_queue(queue.id)
    assert retrieved_queue.id == queue.id
    assert retrieved_queue.name == annotation_queue_name
    assert retrieved_queue.items_count == 2

    # Remove one thread from queue
    queue.remove_threads([threads[0]])

    # Verify queue items count after removal
    def check_items_count_after_removal():
        updated_queue = opik_client.get_annotation_queue(queue.id)
        return updated_queue.items_count == 1

    synchronization.wait_for_done(check_items_count_after_removal, timeout=30)

    # Cleanup: delete the queue
    queue.delete()

    # Verify queue is deleted
    with pytest.raises(opik.exceptions.OpikException):
        opik_client.get_annotation_queue(queue.id)
