import threading
import time

import pytest

from opik.message_processing import message_queue


def test_message_queue_accept_put_without_discarding___max_size_handling():
    max_size = 10
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue(max_size)

    queue.put("the oldest message")
    assert queue.accept_put_without_discarding() is True

    for i in range(max_size - 1):
        assert queue.accept_put_without_discarding() is True, f"Failed at {i}"
        queue.put(f"line: {i}")

    assert queue.accept_put_without_discarding() is False

    # put one more message to have the oldest discarded
    queue.put("the newest message")

    # check that the oldest message ("the oldest message") was discarded
    assert queue.get(0.0001) == "line: 0"


def test_message_queue_accept_put_without_discarding___max_size_none():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue(None)
    for i in range(100):
        queue.put(f"line: {i}")

    assert queue.accept_put_without_discarding() is True


def test_message_queue_task_done__fresh_queue__reports_all_tasks_done():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()
    assert queue.all_tasks_done() is True


def test_message_queue_task_done__put_then_task_done__quiescence_restored():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()

    queue.put("msg-1")
    assert queue.all_tasks_done() is False

    queue.get(0.0001)
    # Popping does not mark the task done — it's in flight until task_done().
    assert queue.all_tasks_done() is False

    queue.task_done()
    assert queue.all_tasks_done() is True


def test_message_queue_task_done__multiple_puts__decremented_one_by_one():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()

    for i in range(5):
        queue.put(f"msg-{i}")

    assert queue.all_tasks_done() is False

    for _ in range(4):
        queue.get(0.0001)
        queue.task_done()
        assert queue.all_tasks_done() is False

    queue.get(0.0001)
    queue.task_done()
    assert queue.all_tasks_done() is True


def test_message_queue_task_done__put_back_does_not_increment_counter():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()

    queue.put("msg")
    msg = queue.get(0.0001)
    # Re-enqueue (delivery-time deferral / rate-limit retry): the task is
    # still in flight, so the counter must not change.
    queue.put_back(msg)
    assert queue.all_tasks_done() is False

    queue.get(0.0001)
    queue.task_done()
    assert queue.all_tasks_done() is True


def test_message_queue_task_done__called_too_many_times__raises():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()

    queue.put("msg")
    queue.get(0.0001)
    queue.task_done()

    with pytest.raises(ValueError):
        queue.task_done()


def test_message_queue_task_done__maxlen_eviction__does_not_increment_counter():
    # When a bounded queue is full, appendleft silently evicts the oldest
    # item. The displaced message's task carries over to the newcomer, so
    # the unfinished-task count must not grow.
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue(max_length=2)

    queue.put("a")
    queue.put("b")
    # Now full — this put evicts the oldest end.
    queue.put("c")

    # Only two messages are reachable; only two task_done() calls should
    # bring the queue back to quiescence.
    queue.get(0.0001)
    queue.task_done()
    queue.get(0.0001)
    queue.task_done()
    assert queue.all_tasks_done() is True


def test_message_queue_put_back__bounded_queue_full__evicted_task_is_released():
    # Regression: on a bounded queue, `put_back` (used by the rate-limit
    # retry path) silently evicts an item from the opposite end. That
    # evicted item was already counted in `_unfinished_tasks` at its
    # original `put()` time and will never see `task_done()` — so the
    # counter must drop by 1 to avoid stalling `join()`.
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue(max_length=2)

    queue.put("a")  # unfinished=1
    queue.put("b")  # unfinished=2; deque [b, a]

    # Simulate a consumer popping a message and being rate-limited mid-process.
    msg = queue.get(0.0001)  # deque [b]; unfinished=2 (msg still in flight)
    assert msg == "a"

    queue.put("c")  # unfinished=3; deque [c, b]

    # Re-enqueue the popped message: the deque is full, so `append` evicts
    # "c" from the left. "c" will never be processed, so its task slot must
    # be released.
    queue.put_back(msg)

    # Two reachable messages — two task_done() calls must restore quiescence.
    queue.get(0.0001)
    queue.task_done()
    queue.get(0.0001)
    queue.task_done()
    assert queue.all_tasks_done() is True
    assert queue.join(timeout=0.05) is True


def test_message_queue_clear__resets_counter_and_unblocks_join():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()

    for i in range(3):
        queue.put(f"msg-{i}")

    assert queue.all_tasks_done() is False

    queue.clear()

    # clear() drops messages that will never see task_done(); the counter
    # must be reset, otherwise join()/all_tasks_done() would stall forever.
    assert queue.all_tasks_done() is True
    assert queue.join(timeout=0.05) is True


def test_message_queue_join__no_pending_tasks__returns_true_immediately():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()
    start = time.monotonic()
    assert queue.join(timeout=1.0) is True
    assert time.monotonic() - start < 0.1


def test_message_queue_join__pending_tasks_with_timeout__returns_false():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()
    queue.put("msg")

    start = time.monotonic()
    assert queue.join(timeout=0.1) is False
    elapsed = time.monotonic() - start
    assert 0.1 <= elapsed < 0.5


def test_message_queue_join__task_done_in_other_thread__wakes_waiter():
    queue: message_queue.MessageQueue[str] = message_queue.MessageQueue()
    queue.put("msg")
    queue.get(0.0001)

    def _consumer() -> None:
        time.sleep(0.05)
        queue.task_done()

    threading.Thread(target=_consumer, daemon=True).start()

    start = time.monotonic()
    assert queue.join(timeout=2.0) is True
    elapsed = time.monotonic() - start
    # Should wake up shortly after the 0.05s sleep, not wait the full timeout.
    assert elapsed < 1.0


@pytest.mark.parametrize(
    "maximal_queue_size,batch_factor,expected",
    [
        (100, 10, 10),
        (100, 0, 100),
    ],
)
def test_calculate_max_queue_size(maximal_queue_size, batch_factor, expected):
    assert (
        message_queue.calculate_max_queue_size(maximal_queue_size, batch_factor)
        == expected
    )
