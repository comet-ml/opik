import threading
from unittest import mock

from opik.evaluation.engine import evaluation_tasks_executor


def test_get_results__done_callbacks_still_running__waits_for_them_and_returns_every_result():
    # A future wakes its waiters before it runs its done-callbacks. Hold every callback briefly so
    # get_results is certain to be waiting while they are still pending.
    # Tasks finish only after both are submitted, so their callbacks run on the pool's threads.
    tasks_may_finish = threading.Event()
    release_callbacks = threading.Event()
    released = []
    completed_callbacks = []
    on_future_done = evaluation_tasks_executor.StreamingExecutor._on_future_done

    def delayed_on_future_done(self, future):
        released.append(release_callbacks.wait(timeout=5))
        on_future_done(self, future)
        completed_callbacks.append(future)

    with (
        mock.patch("opik.environment.get_tqdm_for_current_environment"),
        mock.patch.object(
            evaluation_tasks_executor.StreamingExecutor,
            "_on_future_done",
            delayed_on_future_done,
        ),
    ):
        with evaluation_tasks_executor.StreamingExecutor[str](
            workers=2, verbose=0, client=mock.Mock(), total=2
        ) as executor:
            executor.submit(lambda: tasks_may_finish.wait(timeout=5) and "first")
            executor.submit(lambda: tasks_may_finish.wait(timeout=5) and "second")
            tasks_may_finish.set()

            timer = threading.Timer(0.2, release_callbacks.set)
            timer.start()
            results = executor.get_results()
            callbacks_completed_when_returned = len(completed_callbacks)
            timer.join()

    assert results == ["first", "second"]
    assert released == [True, True]
    assert callbacks_completed_when_returned == 2
