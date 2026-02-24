from unittest import mock

import pytest

from opik.rest_api import core as rest_api_core


@pytest.fixture
def mock_backend():
    """Sets up a mock Opik backend for config decorator tests.

    By default the backend returns a 404 for get_blueprint (no existing blueprint),
    so _sync_config_with_backend will call create_config with all local fields.
    Use set_blueprint_values() to simulate an existing blueprint.
    """
    with mock.patch(
        "opik.api_objects.opik_client.get_client_cached"
    ) as mock_get_client:
        mock_client = mock.Mock()
        mock_get_client.return_value = mock_client

        mock_client.rest_client.optimizer_configs.create_config.return_value = (
            mock.Mock(id="cfg-test")
        )

        # Default: no blueprint exists yet (404)
        mock_client.rest_client.optimizer_configs.get_blueprint.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )
        mock_client.rest_client.optimizer_configs.get_blueprint.return_value = None

        # After create_config, a get_blueprint call is made internally by create_config.
        # We wire up a separate return so create_config's internal get works.
        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-test"
        )

        class _Backend:
            client = mock_client
            optimizer_configs = mock_client.rest_client.optimizer_configs

            @staticmethod
            def set_no_blueprint():
                mock_client.rest_client.optimizer_configs.get_blueprint.side_effect = (
                    rest_api_core.ApiError(status_code=404, body="not found")
                )

            @staticmethod
            def set_blueprint_values(values):
                mock_client.rest_client.optimizer_configs.get_blueprint.side_effect = (
                    None
                )
                mock_client.rest_client.optimizer_configs.get_blueprint.return_value = (
                    mock.Mock(id="bp-test", values=values, description=None)
                )

        yield _Backend()

        from opik.api_objects.agent_config.cache import clear_shared_caches

        clear_shared_caches()
