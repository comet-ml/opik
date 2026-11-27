import time
from concurrent import futures
from unittest import mock

from opik.evaluation.engine import evaluation_tasks_executor


def _add_late_done_callback(original_add_done_callback):
    # A future wakes its waiters before it runs its done-callbacks, so on a busy
    # machine a callback can still be pending when wait() returns. The delay makes
    # that ordering certain.
    def add_done_callback(future, callback):
        def late_callback(done_future):
            time.sleep(0.2)
            callback(done_future)

        original_add_done_callback(future, late_callback)

    return add_done_callback


def test_execute__done_callbacks_run_after_wait_returns__every_result_returned():
    with mock.patch.object(
        futures.Future,
        "add_done_callback",
        _add_late_done_callback(futures.Future.add_done_callback),
    ):
        results = evaluation_tasks_executor.execute(
            evaluation_tasks=[lambda: "first", lambda: "second"],
            workers=2,
            verbose=0,
            client=mock.Mock(),
        )

    assert sorted(results) == ["first", "second"]
