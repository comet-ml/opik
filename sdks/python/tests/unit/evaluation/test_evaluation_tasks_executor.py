import threading
from unittest import mock

from opik.evaluation.engine import evaluation_tasks_executor


def test_get_results__done_callbacks_still_running__returns_every_result():
    # A future wakes its waiters before it runs its done-callbacks, so get_results can return while a
    # callback is still pending. Hold every callback until get_results has returned to make that certain.
    get_results_returned = threading.Event()
    on_future_done = evaluation_tasks_executor.StreamingExecutor._on_future_done

    def delayed_on_future_done(self, future):
        get_results_returned.wait(timeout=5)
        on_future_done(self, future)

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
            executor.submit(lambda: "first")
            executor.submit(lambda: "second")

            results = executor.get_results()
            get_results_returned.set()

    assert results == ["first", "second"]
