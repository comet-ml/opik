"""A shutdown must replay what is parked before it tears the replay store down.

`Streamer.flush()` is what actually replays, and it can only do so while the
replay manager (and the SQLite store behind it) is alive and while the streamer
is not yet draining — `put()` drops everything once `_drain` is set. The
shutdown path used to set `_drain` and close the replay manager *first*, so the
flush that followed replayed nothing and the messages parked by a temporary
server outage were deleted while `FlushResult` reported success.
"""

import datetime
import logging
import time
from typing import Tuple
from unittest import mock

import pytest

from opik.api_objects import opik_client
from opik.healthcheck import connection_monitor
from opik.message_processing import (
    data_loss,
    flush_reporter,
    messages,
    streamer,
    streamer_constructors,
)
from opik.message_processing.replay import db_manager, replay_manager

LOGGER = logging.getLogger(__name__)

PARKED_TRACE_IDS = ["trace-0", "trace-1", "trace-2"]


def _create_trace_message(
    message_id: int, trace_id: str
) -> messages.CreateTraceMessage:
    msg = messages.CreateTraceMessage(
        trace_id=trace_id,
        project_name="test-project",
        name="test-trace",
        start_time=datetime.datetime(2024, 1, 1, 12, 0, 0),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1),
        input={"query": "test"},
        output={"answer": "response"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=None,
        source="sdk",
    )
    msg.message_id = message_id
    return msg


def _make_replay_manager() -> replay_manager.ReplayManager:
    monitor = mock.MagicMock(spec=connection_monitor.OpikConnectionMonitor)
    monitor.has_server_connection = True
    monitor.tick.return_value = connection_monitor.ConnectionStatus.connection_ok
    return replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=10,
        batch_replay_delay=0.01,
        tick_interval_seconds=0.05,
    )


def _build(
    fake_file_upload_manager,
) -> Tuple[streamer.Streamer, replay_manager.ReplayManager, mock.Mock]:
    rm = _make_replay_manager()
    processor = mock.Mock()
    st = streamer_constructors.construct_streamer(
        message_processor=processor,
        n_consumers=1,
        use_batching=False,
        use_attachment_extraction=False,
        file_uploader=fake_file_upload_manager,
        max_queue_size=None,
        fallback_replay_manager=rm,
    )
    return st, rm, processor


def _park_failed(rm: replay_manager.ReplayManager) -> None:
    """Park messages the way a temporary server outage does."""
    for i, trace_id in enumerate(PARKED_TRACE_IDS):
        rm.database_manager.register_message(
            _create_trace_message(message_id=i + 1, trace_id=trace_id),
            status=db_manager.MessageStatus.failed,
        )


def _delivered(processor: mock.Mock) -> list:
    return sorted(call.args[0].trace_id for call in processor.process.call_args_list)


@pytest.fixture
def streamer_stack(fake_file_upload_manager):
    """A streamer on a real replay store, torn down whatever the test does.

    A yield fixture rather than a try/finally in each test: a failing assertion in
    the middle of a test would otherwise skip the cleanup and leave the consumer
    thread and the replay manager's SQLite connection open for the rest of the
    session.
    """
    st, rm, processor = _build(fake_file_upload_manager)
    try:
        yield st, rm, processor
    finally:
        st.close(flush=False)


class TestReplayBeforeShutdownTeardown:
    def test_flush_alone__replays_parked_messages(self, streamer_stack):
        """The control: `flush()` on its own already replays them correctly."""
        st, rm, processor = streamer_stack

        _park_failed(rm)
        assert rm.database_manager.failed_messages_count() == 3

        flushed = st.flush(timeout=5)
        time.sleep(0.3)

        assert flushed is True
        assert _delivered(processor) == PARKED_TRACE_IDS

    def test_close_flush__replays_parked_messages_before_teardown(self, streamer_stack):
        """`close(flush=True)` must replay, not destroy.

        The caller is told everything was fine (`flushed is True`,
        `FlushResult.success`, `dropped_items == 0`) while all three traces were
        deleted from the replay store, so this is a silent loss on the default
        shutdown path -- which is also what the `atexit` hook uses.
        """
        st, rm, processor = streamer_stack
        tracker = data_loss.DataLossTracker()
        reporter = flush_reporter.FlushReporter(streamer=st, data_loss_tracker=tracker)

        _park_failed(rm)
        assert rm.database_manager.failed_messages_count() == 3

        marker = reporter.marker()
        flushed = st.close(timeout=5, flush=True)
        result = reporter.build_result(marker, flushed=flushed)
        time.sleep(0.3)

        assert flushed is True
        assert result.success is True
        assert result.dropped_messages == 0
        assert result.dropped_items == 0

        # Every parked trace has to have reached the processor. On main the
        # replay store was closed before the flush, so none of them did.
        assert _delivered(processor) == PARKED_TRACE_IDS


def test_opik_end__flush__replays_parked_messages_through_the_public_boundary(
    fake_backend, fake_replay_manager
):
    """The same guarantee has to hold at the boundary users actually call.

    `Streamer.close()` is what the two tests above drive. The durable shutdown a
    user performs is `Opik.end()`, which reaches the streamer through
    `ConnectionResourceManager.release()` -> `SharedConnectionResourcesBundle.close()`,
    and that indirection is where a regression could hide: the streamer-level
    tests stop short of it, and the manager-level tests use a fake bundle with no
    replay store at all.

    `fake_backend` keeps the transport real where it matters -- the streamer, its
    consumer thread and the replay manager's SQLite store are all genuine -- and
    only swaps the REST client for the backend emulator, so `process()` records
    exactly the messages that reached delivery.
    """
    client = opik_client.Opik(_show_misconfiguration_message=False)

    _park_failed(fake_replay_manager)
    assert fake_replay_manager.database_manager.failed_messages_count() == 3

    result = client.end(timeout=5)
    time.sleep(0.3)

    assert result is not None
    assert result.success is True
    assert result.dropped_messages == 0
    assert result.dropped_items == 0
    assert sorted(tree.id for tree in fake_backend.trace_trees) == PARKED_TRACE_IDS
