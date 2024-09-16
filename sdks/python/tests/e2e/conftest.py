import os
import random
import string

import opik
import opik.api_objects.opik_client

import pytest


def _random_chars(n: int = 6) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


@pytest.fixture(scope="session")
def configure_e2e_tests_env():
    os.environ["OPIK_PROJECT_NAME"] = "e2e-tests"


@pytest.fixture()
def opik_client(configure_e2e_tests_env, shutdown_cached_client_after_test):
    opik_client_ = opik.api_objects.opik_client.Opik()

    yield opik_client_

    opik_client_.end()


@pytest.fixture
def dataset_name(opik_client: opik.Opik):
    name = f"e2e-tests-dataset-{ _random_chars()}"
    yield name


@pytest.fixture
def experiment_name(opik_client: opik.Opik):
    name = f"e2e-tests-experiment-{ _random_chars()}"
    yield name
