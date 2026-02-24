from unittest import mock

import pytest


@pytest.fixture
def mock_backend():
    """Sets up a mock Opik backend for config decorator tests."""
    with mock.patch(
        "opik.api_objects.opik_client.get_client_cached"
    ) as mock_get_client:
        mock_client = mock.Mock()
        mock_get_client.return_value = mock_client

        mock_client.rest_client.optimizer_configs.create_config.return_value = (
            mock.Mock(id="cfg-test")
        )
        mock_client.rest_client.optimizer_configs.get_blueprint.return_value = (
            mock.Mock(
                id="bp-test",
                values=[mock.Mock(key="temp", type="number", value=0.8)],
                description=None,
            )
        )

        class _Backend:
            client = mock_client
            optimizer_configs = mock_client.rest_client.optimizer_configs

            @staticmethod
            def set_blueprint_values(values):
                mock_client.rest_client.optimizer_configs.get_blueprint.return_value = (
                    mock.Mock(id="bp-test", values=values, description=None)
                )

        yield _Backend()
