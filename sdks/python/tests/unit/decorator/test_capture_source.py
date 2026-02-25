from unittest import mock


from opik.decorator import tracker, inspect_helpers


def test_capture_source__enabled__function_works(fake_backend):
    @tracker.track(capture_source=True)
    def my_function(x, y):
        return x + y

    result = my_function(1, 2)
    assert result == 3

    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1


def test_capture_source__disabled_by_default__function_works(fake_backend):
    @tracker.track()
    def my_function(x, y):
        return x + y

    result = my_function(1, 2)
    assert result == 3

    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1


def test_capture_source__source_extraction_fails__function_still_works():
    mock_get_function_source = mock.Mock()
    mock_get_function_source.return_value = None

    with mock.patch(
        "opik.decorator.inspect_helpers.get_function_source", mock_get_function_source
    ):

        @tracker.track(capture_source=True)
        def my_function(x, y):
            return x + y

        result = my_function(1, 2)
        assert result == 3

        mock_get_function_source.assert_called()


def test_capture_source__nested_functions__both_work(fake_backend):
    @tracker.track(capture_source=True)
    def outer_function(x):
        @tracker.track(capture_source=True)
        def inner_function(y):
            return y * 2

        return inner_function(x) + 1

    result = outer_function(5)
    assert result == 11

    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1


def test_get_function_source__regular_function__returns_source():
    def sample_function():
        return 42

    source = inspect_helpers.get_function_source(sample_function)
    assert source is not None
    assert "def sample_function():" in source
    assert "return 42" in source


def test_get_function_source__lambda__can_extract_source():
    lambda_func = lambda x: x + 1  # noqa: E731
    source = inspect_helpers.get_function_source(lambda_func)
    assert source is not None
    assert "lambda" in source


def test_get_function_source__builtin__returns_none():
    source = inspect_helpers.get_function_source(len)
    assert source is None
