import collections
import time
from queue import Empty
from threading import Condition
from typing import TypeVar, Optional, Generic

T = TypeVar("T")


class MessageQueue(Generic[T]):
    """A thread-safe message queue implementation using a double-ended queue (deque).

    This queue allows putting messages at either end and retrieving them in a FIFO manner.
    It supports optional maximum length limiting and blocking retrieval with timeout.

    Once a bounded length queue is full, when new items are added, a corresponding number
    of items are discarded from the opposite end.

    Args:
        max_length (Optional[int]): Maximum number of messages the queue can hold.
            If None, the queue size is unlimited.

    The queue implements the following operations:
    - put(): Add a message to the front of the queue
    - put_back(): Add a message to the back of the queue
    - get(): Remove and return message from the back of the queue
    - empty(): Check if the queue is empty
    """

    def __init__(self, max_length: Optional[int] = None):
        self.deque: collections.deque[T] = collections.deque(maxlen=max_length)
        self.not_empty = Condition()

    def put(self, message: T) -> None:
        self.deque.appendleft(message)
        self.not_empty.notify()

    def put_back(self, message: T) -> None:
        self.deque.append(message)
        self.not_empty.notify()

    def get(self, timeout: float) -> T:
        if timeout is None or timeout < 0:
            raise ValueError("'timeout' must be a non-negative number")

        endtime = time.monotonic() + timeout
        while len(self.deque) == 0:
            remaining = endtime - time.monotonic()
            if remaining <= 0.0:
                raise Empty
            self.not_empty.wait(remaining)

        try:
            return self.deque.pop()
        except IndexError:
            raise Empty

    def empty(self) -> bool:
        return len(self.deque) == 0

    def __len__(self) -> int:
        return len(self.deque)
