import threading
from unittest import mock

from opik import opik_context
from opik.api_objects import opik_client
from opik.decorator import tracker


def test_track__get_client_cached_raised_error__error_is_caught__user_function_is_not_affected():
    mock_get_client_cached = mock.Mock()
    mock_get_client_cached.side_effect = Exception

    with mock.patch.object(opik_client, "get_client_cached", mock_get_client_cached):

        @tracker.track()
        def f():
            return 42

        assert f() == 42

        mock_get_client_cached.assert_called_once()


def test_track__using_distributed_headers__headers_are_set_correctly(fake_backend):
    @tracker.track
    def inner_thread(x):
        print("Inner thread")
        return "inner-thread-output"

    @tracker.track
    def top_thread(x):
        print("TOP THREAD")
        inner_thread(x)
        return "top-thread-output"

    @tracker.track
    def do_distributed_trace():
        headers = opik_context.get_distributed_trace_headers()

        t = threading.Thread(
            target=top_thread,
            kwargs={
                "x": "inner-thread-input",
                "opik_distributed_trace_headers": headers,
            },
        )
        t.start()
        t.join()

        return "distributed-trace-output"

    do_distributed_trace()
