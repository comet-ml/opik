from unittest.mock import Mock
import pytest

from opik.api_objects.annotation_queue.annotation_queue import (
    TracesAnnotationQueue,
    ThreadsAnnotationQueue,
)
from opik.rest_api.types import trace_public, trace_thread
from opik.exceptions import OpikException


class TestTracesAnnotationQueueProperties:
    def test_id__returns_id(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        assert queue.id == "queue-123"

    def test_name__returns_name(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        assert queue.name == "test_queue"

    def test_scope__returns_trace(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        assert queue.scope == "trace"

    def test_items_count__cached_value__returns_cached_count(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            items_count=10,
        )

        assert queue.items_count == 10
        mock_rest_client.annotation_queues.get_annotation_queue_by_id.assert_not_called()

    def test_items_count__no_cached_value__fetches_from_backend(self):
        mock_rest_client = Mock()
        mock_rest_client.annotation_queues.get_annotation_queue_by_id.return_value = (
            Mock(items_count=25)
        )

        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            items_count=None,
        )

        assert queue.items_count == 25
        mock_rest_client.annotation_queues.get_annotation_queue_by_id.assert_called_once_with(
            "queue-123"
        )


class TestThreadsAnnotationQueueProperties:
    def test_scope__returns_thread(self):
        mock_rest_client = Mock()
        queue = ThreadsAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        assert queue.scope == "thread"


class TestTracesAnnotationQueueUpdate:
    def test_update__happyflow(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            description="old description",
        )

        queue.update(
            name="new_name",
            description="new description",
            instructions="new instructions",
        )

        mock_rest_client.annotation_queues.update_annotation_queue.assert_called_once_with(
            id="queue-123",
            name="new_name",
            description="new description",
            instructions="new instructions",
            comments_enabled=None,
            feedback_definition_names=None,
        )

        assert queue.name == "new_name"
        assert queue.description == "new description"
        assert queue.instructions == "new instructions"

    def test_update__partial_update__only_specified_fields_updated(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            description="old description",
            instructions="old instructions",
        )

        queue.update(description="new description")

        mock_rest_client.annotation_queues.update_annotation_queue.assert_called_once_with(
            id="queue-123",
            name=None,
            description="new description",
            instructions=None,
            comments_enabled=None,
            feedback_definition_names=None,
        )

        assert queue.name == "test_queue"
        assert queue.description == "new description"
        assert queue.instructions == "old instructions"


class TestTracesAnnotationQueueDelete:
    def test_delete__happyflow(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        queue.delete()

        mock_rest_client.annotation_queues.delete_annotation_queue_batch.assert_called_once_with(
            ids=["queue-123"]
        )


class TestTracesAnnotationQueueAddTraces:
    def test_add_traces__single_trace_in_list__happyflow(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            items_count=5,
        )

        trace = Mock(spec=trace_public.TracePublic)
        trace.id = "trace-1"

        queue.add_traces([trace])

        mock_rest_client.annotation_queues.add_items_to_annotation_queue.assert_called_once_with(
            id="queue-123", ids=["trace-1"]
        )
        assert queue._items_count is None

    def test_add_traces__multiple_traces__happyflow(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        traces = [
            Mock(spec=trace_public.TracePublic, id="trace-1"),
            Mock(spec=trace_public.TracePublic, id="trace-2"),
            Mock(spec=trace_public.TracePublic, id="trace-3"),
        ]

        queue.add_traces(traces)

        mock_rest_client.annotation_queues.add_items_to_annotation_queue.assert_called_once_with(
            id="queue-123", ids=["trace-1", "trace-2", "trace-3"]
        )

    def test_add_traces__trace_without_id__raises_exception(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        trace = Mock(spec=trace_public.TracePublic)
        trace.id = None

        with pytest.raises(OpikException) as exc_info:
            queue.add_traces([trace])

        assert "no id" in str(exc_info.value)

    def test_add_traces__empty_list__no_api_call(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        queue.add_traces([])

        mock_rest_client.annotation_queues.add_items_to_annotation_queue.assert_not_called()


class TestThreadsAnnotationQueueAddThreads:
    def test_add_threads__single_thread_in_list__happyflow(self):
        mock_rest_client = Mock()
        queue = ThreadsAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            items_count=5,
        )

        thread = Mock(spec=trace_thread.TraceThread)
        thread.thread_model_id = "thread-model-1"

        queue.add_threads([thread])

        mock_rest_client.annotation_queues.add_items_to_annotation_queue.assert_called_once_with(
            id="queue-123", ids=["thread-model-1"]
        )
        assert queue._items_count is None

    def test_add_threads__multiple_threads__happyflow(self):
        mock_rest_client = Mock()
        queue = ThreadsAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        thread1 = Mock(spec=trace_thread.TraceThread)
        thread1.thread_model_id = "thread-model-1"
        thread2 = Mock(spec=trace_thread.TraceThread)
        thread2.thread_model_id = "thread-model-2"
        threads = [thread1, thread2]

        queue.add_threads(threads)

        mock_rest_client.annotation_queues.add_items_to_annotation_queue.assert_called_once_with(
            id="queue-123", ids=["thread-model-1", "thread-model-2"]
        )

    def test_add_threads__thread_without_thread_model_id__raises_exception(self):
        mock_rest_client = Mock()
        queue = ThreadsAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
        )

        thread = Mock(spec=trace_thread.TraceThread)
        thread.thread_model_id = None

        with pytest.raises(OpikException) as exc_info:
            queue.add_threads([thread])

        assert "thread_model_id" in str(exc_info.value)


class TestTracesAnnotationQueueRemoveTraces:
    def test_remove_traces__single_trace_in_list__happyflow(self):
        mock_rest_client = Mock()
        queue = TracesAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            items_count=5,
        )

        trace = Mock(spec=trace_public.TracePublic)
        trace.id = "trace-1"

        queue.remove_traces([trace])

        mock_rest_client.annotation_queues.remove_items_from_annotation_queue.assert_called_once_with(
            id="queue-123", ids=["trace-1"]
        )
        assert queue._items_count is None


class TestThreadsAnnotationQueueRemoveThreads:
    def test_remove_threads__single_thread_in_list__happyflow(self):
        mock_rest_client = Mock()
        queue = ThreadsAnnotationQueue(
            id="queue-123",
            name="test_queue",
            project_id="project-123",
            rest_client=mock_rest_client,
            items_count=5,
        )

        thread = Mock(spec=trace_thread.TraceThread)
        thread.thread_model_id = "thread-model-1"

        queue.remove_threads([thread])

        mock_rest_client.annotation_queues.remove_items_from_annotation_queue.assert_called_once_with(
            id="queue-123", ids=["thread-model-1"]
        )
        assert queue._items_count is None
