import threading
from unittest import mock

from opik.evaluation.engine import evaluation_tasks_executor


def test_get_results__done_callbacks_still_running__waits_for_them_and_returns_every_result():
    # A future wakes its waiters before it runs its done-callbacks. Block both callbacks, check that
    # get_results is still waiting for them, then release them.
    tasks_may_finish = threading.Event()
    release_callbacks = threading.Event()
    callbacks_started = threading.Semaphore(0)
    completed_callbacks = []
    on_future_done = evaluation_tasks_executor.StreamingExecutor._on_future_done

    def blocked_on_future_done(self, future):
        callbacks_started.release()
        release_callbacks.wait(timeout=5)
        on_future_done(self, future)
        completed_callbacks.append(future)

    returned = {}

    def collect_results(executor):
        returned["results"] = executor.get_results()
        returned["callbacks_completed"] = len(completed_callbacks)

    with (
        mock.patch("opik.environment.get_tqdm_for_current_environment"),
        mock.patch.object(
            evaluation_tasks_executor.StreamingExecutor,
            "_on_future_done",
            blocked_on_future_done,
        ),
    ):
        with evaluation_tasks_executor.StreamingExecutor[str](
            workers=2, verbose=0, client=mock.Mock(), total=2
        ) as executor:
            # Tasks finish only after both are submitted, so their callbacks run on the pool's threads.
            executor.submit(lambda: tasks_may_finish.wait(timeout=5) and "first")
            executor.submit(lambda: tasks_may_finish.wait(timeout=5) and "second")
            tasks_may_finish.set()

            collector = threading.Thread(target=collect_results, args=(executor,))
            collector.start()
            try:
                both_started = callbacks_started.acquire(
                    timeout=5
                ) and callbacks_started.acquire(timeout=5)
                collector.join(timeout=0.2)
                returned_while_callbacks_blocked = not collector.is_alive()
            finally:
                release_callbacks.set()
                collector.join(timeout=5)

    assert both_started
    assert not returned_while_callbacks_blocked
    assert returned == {"results": ["first", "second"], "callbacks_completed": 2}
