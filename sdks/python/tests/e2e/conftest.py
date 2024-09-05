import os
from opik import config
from opik.rest_api import client
from opik import httpx_client

import pytest


@pytest.fixture(scope="session")
def configure_e2e_tests_env():
    os.environ["OPIK_PROJECT_NAME"] = "e2e-tests"
    os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:5173/api"


@pytest.fixture(scope="session")
def rest_api_client(configure_e2e_tests_env):
    config_ = config.OpikConfig()
    httpx_client_ = httpx_client.get(workspace=config_.workspace, api_key=None)

    rest_api_client_ = client.OpikApi(
        base_url=config_.url_override, httpx_client=httpx_client_
    )
    return rest_api_client_
