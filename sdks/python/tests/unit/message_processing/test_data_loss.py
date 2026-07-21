from unittest import mock

from opik.message_processing import data_loss, flush_reporter, messages


def _failure(
    reason: data_loss.FailureReason = data_loss.FailureReason.HTTP_CLIENT_ERROR,
    item_count: int = 1,
) -> data_loss.FailedMessageInfo:
    return data_loss.FailedMessageInfo(
        message_type="CreateSpansBatchMessage",
        reason=reason,
        item_count=item_count,
    )


class TestFailureReason:
    def test_from_status_code__4xx__client_error(self):
        assert (
            data_loss.FailureReason.from_status_code(403)
            == data_loss.FailureReason.HTTP_CLIENT_ERROR
        )

    def test_from_status_code__5xx__server_error(self):
        assert (
            data_loss.FailureReason.from_status_code(503)
            == data_loss.FailureReason.HTTP_SERVER_ERROR
        )

    def test_from_status_code__none__unknown(self):
        assert (
            data_loss.FailureReason.from_status_code(None)
            == data_loss.FailureReason.UNKNOWN
        )


class TestFlushResultSuccess:
    def test_success__drained_no_drops__true(self):
        result = data_loss.FlushResult(
            flushed=True,
            remaining_queue_size=0,
            dropped_messages=0,
            dropped_items=0,
            failures=[],
        )
        assert result.success is True

    def test_success__dropped_messages__false(self):
        result = data_loss.FlushResult(
            flushed=True,
            remaining_queue_size=0,
            dropped_messages=1,
            dropped_items=5,
            failures=[_failure(item_count=5)],
        )
        assert result.success is False

    def test_success__not_flushed__false(self):
        result = data_loss.FlushResult(
            flushed=False,
            remaining_queue_size=3,
            dropped_messages=0,
            dropped_items=0,
            failures=[],
        )
        assert result.success is False


class TestDataLossTracker:
    def test_drops_since__records_after_marker__exact_delta(self):
        tracker = data_loss.DataLossTracker()
        tracker.record(_failure())
        marker = tracker.marker()
        tracker.record(_failure(item_count=2))
        tracker.record(_failure(item_count=3))

        count, items, failures = tracker.drops_since(marker)
        assert count == 2
        assert items == 5
        assert len(failures) == 2

    def test_drops_since__no_new_records__empty(self):
        tracker = data_loss.DataLossTracker()
        tracker.record(_failure())
        marker = tracker.marker()

        count, items, failures = tracker.drops_since(marker)
        assert count == 0
        assert items == 0
        assert failures == []

    def test_drops_since__details_evicted__counts_still_exact(self):
        tracker = data_loss.DataLossTracker(max_entries=2)
        marker = tracker.marker()
        for _ in range(5):
            tracker.record(_failure(item_count=4))

        count, items, failures = tracker.drops_since(marker)
        # Both counts are exact (running totals); only the retained details are
        # bounded to capacity.
        assert count == 5
        assert items == 20
        assert len(failures) == 2

    def test_total_drops__exact_counts_and_details(self):
        tracker = data_loss.DataLossTracker()
        tracker.record(_failure())
        tracker.record(_failure(item_count=2))

        count, items, failures = tracker.total_drops()
        assert count == 2
        assert items == 3
        assert len(failures) == 2

    def test_total_drops__details_bounded_but_counts_exact(self):
        tracker = data_loss.DataLossTracker(max_entries=2)
        for _ in range(5):
            tracker.record(_failure())

        count, _items, failures = tracker.total_drops()
        assert count == 5
        assert len(failures) == 2


class TestFlushReporter:
    def _reporter(self, tracker, *, queue_size=0):
        streamer = mock.Mock()
        streamer.queue_size.return_value = queue_size
        return flush_reporter.FlushReporter(streamer, tracker)

    def test_build_result__drop_after_marker__reported_as_data_loss(self):
        tracker = data_loss.DataLossTracker()
        reporter = self._reporter(tracker)
        marker = reporter.marker()
        tracker.record(_failure(item_count=3))

        result = reporter.build_result(marker, flushed=True)

        assert result.success is False
        assert result.dropped_messages == 1
        assert result.dropped_items == 3
        assert result.failures[0].reason == data_loss.FailureReason.HTTP_CLIENT_ERROR

    def test_build_result__no_drops__success(self):
        tracker = data_loss.DataLossTracker()
        reporter = self._reporter(tracker)
        marker = reporter.marker()

        result = reporter.build_result(marker, flushed=True)

        assert result.success is True

    def test_build_errors_report__surfaces_drops_outside_flush_window(self):
        # A drop that happened before the flush window is not in the flush
        # result, but is still discoverable via the sender-wide report.
        tracker = data_loss.DataLossTracker()
        reporter = self._reporter(tracker)
        tracker.record(_failure())

        marker = reporter.marker()
        result = reporter.build_result(marker, flushed=True)
        report = reporter.build_errors_report()

        assert result.dropped_messages == 0
        assert report.total_dropped_messages == 1
        assert len(report.failures) == 1

    def test_build_errors_report__carries_timestamps(self):
        tracker = data_loss.DataLossTracker()
        reporter = self._reporter(tracker)
        tracker.record(
            data_loss.FailedMessageInfo(
                message_type="CreateSpansBatchMessage",
                reason=data_loss.FailureReason.HTTP_CLIENT_ERROR,
                item_count=1,
                timestamp=1000.0,
            )
        )

        report = reporter.build_errors_report()

        assert report.has_data_loss is True
        assert report.generated_at > 0
        assert report.first_failure_at == 1000.0
        assert report.last_failure_at == 1000.0

    def test_build_errors_report__no_drops__empty(self):
        tracker = data_loss.DataLossTracker()
        reporter = self._reporter(tracker)

        report = reporter.build_errors_report()

        assert report.has_data_loss is False
        assert report.failures == []
        assert report.first_failure_at is None


class TestMessageItemCount:
    def test_item_count__batch_message__batch_length(self):
        message = messages.CreateSpansBatchMessage(batch=[])
        message.batch = ["span1", "span2", "span3"]
        assert message.item_count == 3

    def test_item_count__non_batch_message__one(self):
        assert messages.BaseMessage().item_count == 1
