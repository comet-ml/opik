from unittest import mock

import pytest

from opik.rest_api import core as rest_api_core


@pytest.fixture
def mock_backend():
    """Sets up a mock Opik backend for config decorator tests.

    By default the backend returns a 404 for get_latest_blueprint (no existing blueprint),
    so _sync_config_with_backend will call create_agent_config with all local fields.
    Use set_blueprint_values() to simulate an existing blueprint.
    """
    with mock.patch(
        "opik.api_objects.opik_client.get_client_cached"
    ) as mock_get_client:
        mock_client = mock.Mock()
        mock_get_client.return_value = mock_client

        mock_client.rest_client.agent_configs.create_agent_config.return_value = None

        # Default: no blueprint exists yet (404)
        mock_client.rest_client.agent_configs.get_latest_blueprint.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )
        mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = None

        mock_client.rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )
        mock_client.rest_client.agent_configs.get_blueprint_by_env.return_value = None

        # Called after create_blueprint to fetch the exact object just created.
        mock_client.rest_client.agent_configs.get_blueprint_by_id.return_value = (
            mock.Mock(id="bp-test", values=[], description=None)
        )

        mock_client.rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-test"
        )

        class _Backend:
            client = mock_client
            agent_configs = mock_client.rest_client.agent_configs

            @staticmethod
            def set_no_blueprint():
                mock_client.rest_client.agent_configs.get_latest_blueprint.side_effect = rest_api_core.ApiError(
                    status_code=404, body="not found"
                )
                mock_client.rest_client.agent_configs.get_blueprint_by_env.side_effect = rest_api_core.ApiError(
                    status_code=404, body="not found"
                )

            @staticmethod
            def set_blueprint_values(values):
                mock_client.rest_client.agent_configs.get_latest_blueprint.side_effect = None
                mock_client.rest_client.agent_configs.get_latest_blueprint.return_value = mock.Mock(
                    id="bp-test", values=values, description=None
                )
                mock_client.rest_client.agent_configs.get_blueprint_by_env.side_effect = None
                mock_client.rest_client.agent_configs.get_blueprint_by_env.return_value = mock.Mock(
                    id="bp-test", values=values, description=None
                )
                mock_client.rest_client.agent_configs.get_blueprint_by_id.return_value = mock.Mock(
                    id="bp-test", values=values, description=None
                )

        yield _Backend()

        from opik.api_objects.agent_config.cache import clear_shared_caches

        clear_shared_caches()
