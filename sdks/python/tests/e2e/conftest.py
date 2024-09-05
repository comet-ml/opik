import os

import opik.api_objects.opik_client

import pytest


@pytest.fixture(scope="session")
def configure_e2e_tests_env():
    os.environ["OPIK_PROJECT_NAME"] = "e2e-tests"
    os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:5173/api"


@pytest.fixture(scope="session")
def opik_client(configure_e2e_tests_env):
    opik_client_ = opik.api_objects.opik_client.get_client_cached()

    return opik_client_
