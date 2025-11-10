from typing import Final
import os
import tempfile
import numpy as np
import pytest

import opik
import opik.api_objects.opik_client
from .. import testlib
from ..conftest import random_chars

OPIK_E2E_TESTS_PROJECT_NAME: Final[str] = "e2e-tests"
ATTACHMENT_FILE_SIZE = 2 * 1024 * 1024


@pytest.fixture()
def configure_e2e_tests_env():
    with testlib.patch_environ({"OPIK_PROJECT_NAME": OPIK_E2E_TESTS_PROJECT_NAME}):
        yield


@pytest.fixture()
def opik_client(configure_e2e_tests_env, shutdown_cached_client_after_test):
    opik_client_ = opik.api_objects.opik_client.Opik(_use_batching=True)

    yield opik_client_

    opik_client_.end()


@pytest.fixture
def dataset_name(opik_client: opik.Opik):
    name = f"e2e-tests-dataset-{random_chars()}"
    yield name


@pytest.fixture
def experiment_name(opik_client: opik.Opik):
    name = f"e2e-tests-experiment-{random_chars()}"
    yield name


@pytest.fixture
def temporary_project_name(opik_client: opik.Opik):
    name = f"e2e-tests-temporary-project-{random_chars()}"
    yield name
    project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
    opik_client.rest_client.projects.delete_project_by_id(project_id)


@pytest.fixture
def attachment_data_file():
    temp_file = tempfile.NamedTemporaryFile(delete=False)
    try:
        temp_file.write(np.random.bytes(ATTACHMENT_FILE_SIZE))
        temp_file.seek(0)
        yield temp_file
    finally:
        temp_file.close()
        os.unlink(temp_file.name)
