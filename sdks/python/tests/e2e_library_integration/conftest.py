from typing import Final

import pytest

import opik
import opik.api_objects.opik_client
from .. import testlib
from ..conftest import random_chars

OPIK_E2E_LIB_INTEGRATION_TESTS_PROJECT_NAME: Final[str] = (
    "e2e-libraries-integration-tests"
)


@pytest.fixture()
def configure_e2e_tests_env():
    with testlib.patch_environ(
        {"OPIK_PROJECT_NAME": OPIK_E2E_LIB_INTEGRATION_TESTS_PROJECT_NAME}
    ):
        yield


@pytest.fixture()
def opik_client(
    configure_e2e_tests_env_unique_project_name, shutdown_cached_client_after_test
):
    opik_client_ = opik.api_objects.opik_client.Opik(_use_batching=True)

    yield opik_client_

    opik_client_.end()


@pytest.fixture()
def configure_e2e_tests_env_unique_project_name():
    project_name = f"{OPIK_E2E_LIB_INTEGRATION_TESTS_PROJECT_NAME}-{random_chars()}"
    with testlib.patch_environ({"OPIK_PROJECT_NAME": project_name}):
        yield project_name


@pytest.fixture()
def opik_client_unique_project_name(
    configure_e2e_tests_env_unique_project_name, shutdown_cached_client_after_test
):
    opik_client_ = opik.api_objects.opik_client.Opik(_use_batching=True)

    yield opik_client_

    opik_client_.end()
