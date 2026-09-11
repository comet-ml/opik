from unittest import mock

from opik.api_objects import opik_client
from opik.decorator import tracker


def test_track__get_global_client_raised_error__error_is_caught__user_function_is_not_affected():
    mock_get_global_client = mock.Mock()
    mock_get_global_client.side_effect = Exception

    with mock.patch.object(opik_client, "get_global_client", mock_get_global_client):

        @tracker.track()
        def f():
            return 42

        assert f() == 42

        mock_get_global_client.assert_called()
