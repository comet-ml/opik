import collections
from queue import Empty
import threading
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
        self._deque: collections.deque[T] = collections.deque(maxlen=max_length)
        self._cond = threading.Condition()
        self._unfinished_tasks = 0
        self.max_size = max_length

    def put(self, message: T) -> None:
        with self._cond:
            before = len(self._deque)
            self._deque.appendleft(message)
            # When the deque is at maxlen, appendleft silently evicts from the
            # other end. Only count a new in-flight task when the queue actually
            # grew — otherwise the displaced message's task carries over.
            if len(self._deque) > before:
                self._unfinished_tasks += 1
            self._cond.notify()

    def put_back(self, message: T) -> None:
        # Re-enqueuing a previously-popped message (rate-limit retry / deferred
        # delivery): the message itself is already accounted for in
        # `_unfinished_tasks` from its original `put()`, so we do not
        # increment.
        #
        # On a bounded full queue, however, `append()` silently evicts the
        # leftmost message — which *was* counted at its original `put()` time
        # and will now never see `task_done()`. Detect that case and
        # decrement so `join()` / `all_tasks_done()` don't stall on a
        # phantom task.
        with self._cond:
            before = len(self._deque)
            self._deque.append(message)
            if len(self._deque) == before and self._unfinished_tasks > 0:
                self._unfinished_tasks -= 1
                if self._unfinished_tasks == 0:
                    self._cond.notify_all()
            self._cond.notify()

    def get(self, timeout: float) -> T:
        with self._cond:
            if timeout is None or timeout < 0:
                raise ValueError("'timeout' must be a non-negative number")

            self._cond.wait_for(lambda: len(self._deque) > 0, timeout=timeout)

            if len(self._deque) == 0:
                raise Empty

            return self._deque.pop()

    def task_done(self) -> None:
        """Mark a previously-popped message as terminally handled (processed
        or dropped). Must NOT be called when the message has been re-enqueued
        via ``put_back``."""
        with self._cond:
            if self._unfinished_tasks <= 0:
                raise ValueError("task_done() called more times than there were items")
            self._unfinished_tasks -= 1
            if self._unfinished_tasks == 0:
                self._cond.notify_all()

    def all_tasks_done(self) -> bool:
        with self._cond:
            return self._unfinished_tasks == 0

    def join(self, timeout: Optional[float] = None) -> bool:
        """Block until every accepted message has been marked done via
        ``task_done()``. Returns True if quiescence was reached within
        ``timeout``, False otherwise."""
        with self._cond:
            return self._cond.wait_for(
                lambda: self._unfinished_tasks == 0, timeout=timeout
            )

    def empty(self) -> bool:
        return len(self._deque) == 0

    def clear(self) -> None:
        """Drop all pending messages. Intended for fire-and-forget teardowns."""
        with self._cond:
            self._deque.clear()
            # Discarded messages will never see task_done; reset the counter
            # so future join()/all_tasks_done() callers aren't stuck waiting
            # on phantom in-flight tasks.
            self._unfinished_tasks = 0
            self._cond.notify_all()

    def accept_put_without_discarding(self) -> bool:
        if self.max_size is None:
            return True
        with self._cond:
            return len(self._deque) < self.max_size

    def size(self) -> int:
        return len(self._deque)

    def __len__(self) -> int:
        return len(self._deque)


def calculate_max_queue_size(
    maximal_queue_size: int,
    batch_factor: int,
) -> int:
    if batch_factor > 0:
        return int(maximal_queue_size / batch_factor)
    else:
        return maximal_queue_size
