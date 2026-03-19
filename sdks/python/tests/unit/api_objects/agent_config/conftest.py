from unittest import mock

import pytest

from opik.api_objects import opik_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic


def make_raw_blueprint(blueprint_id="bp-1", name=None, values=None, description=None):
    if values is None:
        values = []
    return AgentBlueprintPublic(
        id=blueprint_id,
        name=name,
        type="blueprint",
        values=values,
        description=description,
    )


@pytest.fixture
def mock_rest_client():
    client = mock.Mock()
    client.agent_configs = mock.Mock()
    client.agent_configs.create_agent_config.return_value = None
    client.agent_configs.get_latest_blueprint.side_effect = rest_api_core.ApiError(
        status_code=404, body="not found"
    )
    client.agent_configs.get_blueprint_by_env.side_effect = rest_api_core.ApiError(
        status_code=404, body="not found"
    )
    client.agent_configs.get_blueprint_by_id.return_value = make_raw_blueprint()
    client.projects.retrieve_project.return_value = mock.Mock(id="proj-test")
    return client


@pytest.fixture
def mock_opik_client(mock_rest_client):
    client = opik_client.Opik.__new__(opik_client.Opik)
    client._rest_client = mock_rest_client
    client._project_name = "test-project"
    return client


@pytest.fixture(autouse=True)
def clear_caches():
    yield
    from opik.api_objects.agent_config.cache import get_global_registry

    get_global_registry().clear()
