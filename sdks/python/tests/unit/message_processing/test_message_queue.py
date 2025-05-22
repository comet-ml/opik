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
