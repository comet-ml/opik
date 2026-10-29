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
from typing import Optional, Tuple
from unittest import mock

import pytest

from opik.api_objects import opik_client
from opik.healthcheck import connection_monitor, connection_probe
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


def _make_disconnected_replay_manager(
    server_is_back: bool,
) -> replay_manager.ReplayManager:
    """A replay manager whose monitor saw the server go down and will not ping
    again on its own for a long while."""
    probe = mock.Mock(spec=connection_probe.ConnectionProbe)
    probe.check_connection.return_value = connection_probe.ProbeResult(
        is_healthy=server_is_back,
        error_message=None if server_is_back else "Connection refused",
    )
    monitor = connection_monitor.OpikConnectionMonitor(
        ping_interval=1000, check_timeout=1, probe=probe
    )
    monitor.connection_failed(failure_reason="Connection refused")
    monitor.last_beat = time.time()
    return replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=10,
        batch_replay_delay=0.01,
        tick_interval_seconds=0.05,
    )


def _build(
    fake_file_upload_manager,
    rm: Optional[replay_manager.ReplayManager] = None,
    data_loss_tracker: Optional[data_loss.DataLossTracker] = None,
) -> Tuple[streamer.Streamer, replay_manager.ReplayManager, mock.Mock]:
    rm = rm or _make_replay_manager()
    processor = mock.Mock()
    st = streamer_constructors.construct_streamer(
        message_processor=processor,
        n_consumers=1,
        use_batching=False,
        use_attachment_extraction=False,
        file_uploader=fake_file_upload_manager,
        max_queue_size=None,
        fallback_replay_manager=rm,
        data_loss_tracker=data_loss_tracker,
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


class TestShutdownWhileServerLooksUnreachable:
    @pytest.fixture
    def build_stack(self, fake_file_upload_manager):
        built = []

        def build(server_is_back: bool):
            tracker = data_loss.DataLossTracker()
            st, rm, processor = _build(
                fake_file_upload_manager,
                rm=_make_disconnected_replay_manager(server_is_back),
                data_loss_tracker=tracker,
            )
            built.append(st)
            reporter = flush_reporter.FlushReporter(
                streamer=st, data_loss_tracker=tracker
            )
            return st, rm, processor, reporter

        yield build
        for st in built:
            st.close(flush=False)

    def test_close_flush__server_still_unreachable__parked_messages_reported_as_dropped(
        self, build_stack
    ):
        st, rm, processor, reporter = build_stack(server_is_back=False)
        _park_failed(rm)

        marker = reporter.marker()
        flushed = st.close(timeout=5, flush=True)
        result = reporter.build_result(marker, flushed=flushed)

        assert flushed is False
        assert result.success is False
        assert result.dropped_messages == 3
        assert result.dropped_items == 3
        assert {failure.reason for failure in result.failures} == {
            data_loss.FailureReason.UNDELIVERED_AT_SHUTDOWN
        }
        assert _delivered(processor) == []

    def test_close_flush__server_back_before_next_scheduled_ping__parked_messages_replayed(
        self, build_stack
    ):
        st, rm, processor, reporter = build_stack(server_is_back=True)
        _park_failed(rm)

        marker = reporter.marker()
        flushed = st.close(timeout=5, flush=True)
        result = reporter.build_result(marker, flushed=flushed)
        time.sleep(0.3)

        assert flushed is True
        assert result.success is True
        assert result.dropped_messages == 0
        assert _delivered(processor) == PARKED_TRACE_IDS


def test_opik_end__server_unreachable__parked_messages_reported_as_dropped(
    fake_file_upload_manager,
):
    """The scenario from the user report: the backend is still down when the
    program ends, so `end()` must report the parked traces as lost instead of
    returning success with nothing dropped.

    `construct_online_streamer` is replaced with one that keeps the tracker the
    connection bundle passes in, so the drops reach `end()`'s `FlushResult`
    exactly as they do in production.
    """
    rm = _make_disconnected_replay_manager(server_is_back=False)
    processor = mock.Mock()

    def construct_online_streamer(**kwargs):
        return streamer_constructors.construct_streamer(
            message_processor=processor,
            n_consumers=1,
            use_batching=False,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=rm,
            data_loss_tracker=kwargs["data_loss_tracker"],
        )

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        side_effect=construct_online_streamer,
    ):
        client = opik_client.Opik(_show_misconfiguration_message=False)
        _park_failed(rm)
        result = client.end(timeout=5)

    assert result is not None
    assert result.success is False
    assert result.dropped_messages == 3
    assert result.dropped_items == 3
    assert _delivered(processor) == []


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
