import random
import string
from typing import Final

import pytest

import opik
import opik.api_objects.opik_client
from .. import testlib

OPIK_E2E_TESTS_PROJECT_NAME: Final[str] = "e2e-tests"


def _random_chars(n: int = 6) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


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
    name = f"e2e-tests-dataset-{_random_chars()}"
    yield name


@pytest.fixture
def experiment_name(opik_client: opik.Opik):
    name = f"e2e-tests-experiment-{_random_chars()}"
    yield name
