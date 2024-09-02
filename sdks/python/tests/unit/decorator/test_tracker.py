import mock

from opik.api_objects import opik_client
from opik.decorator import tracker


def test_track__one_level_of_nesting__trace_and_span_created():
    """
    This test is the only of its kind in this module. The tests
    checking that tracker generates correct messages which lead to correct
    trace trees are located in test_tracker_outputs.py
    """
    mock_get_client_cached = mock.Mock()
    mock_comet = mock.Mock()
    mock_trace = mock.Mock()
    mock_span = mock.Mock()

    mock_trace.span.return_value = mock_span
    mock_comet.trace.return_value = mock_trace
    mock_get_client_cached.return_value = mock_comet

    with mock.patch.object(opik_client, "get_client_cached", mock_get_client_cached):

        @tracker.track(capture_output=True)
        def f(x):
            return f"result-from-{x}"

        f("some-input")

        mock_get_client_cached.assert_called_once()

        mock_comet.trace.assert_called_once_with(
            name="f",
            input={"x": "some-input"},
            metadata=None,
            tags=None,
        )
        mock_trace.span.assert_called_once_with(
            name="f",
            type="general",
            input={"x": "some-input"},
            metadata=None,
            tags=None,
        )
        mock_span.end.assert_called_once_with(
            output={"output": "result-from-some-input"},
        )
        mock_trace.end.assert_called_once_with(
            output={"output": "result-from-some-input"},
        )


def test_track__get_client_cached_raised_error__error_is_caught__user_function_is_not_affected():
    mock_get_client_cached = mock.Mock()
    mock_get_client_cached.side_effect = Exception

    with mock.patch.object(opik_client, "get_client_cached", mock_get_client_cached):

        @tracker.track()
        def f():
            return 42

        assert f() == 42

        mock_get_client_cached.assert_called_once()


def test_track_without_parentheses():
    mock_get_client_cached = mock.Mock()
    mock_comet = mock.Mock()
    mock_trace = mock.Mock()
    mock_span = mock.Mock()

    mock_trace.span.return_value = mock_span
    mock_comet.trace.return_value = mock_trace
    mock_get_client_cached.return_value = mock_comet

    with mock.patch.object(opik_client, "get_client_cached", mock_get_client_cached):

        @tracker.track
        def f(x):
            return f"result-from-{x}"

        result = f("some-input")

        assert result == "result-from-some-input"

        mock_get_client_cached.assert_called_once()

        mock_comet.trace.assert_called_once_with(
            name="f",
            input={"x": "some-input"},
            metadata=None,
            tags=None,
        )
        mock_trace.span.assert_called_once_with(
            name="f",
            type="general",
            input={"x": "some-input"},
            metadata=None,
            tags=None,
        )
        mock_span.end.assert_called_once_with(
            output={"output": "result-from-some-input"},
        )
        mock_trace.end.assert_called_once_with(
            output={"output": "result-from-some-input"},
        )
