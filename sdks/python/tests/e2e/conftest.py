from typing import Final
import os
import tempfile
import numpy as np
import pytest

import opik
import opik.api_objects.opik_client
from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config
from opik.rest_api import core as rest_api_core
from .. import testlib
from ..conftest import random_chars

OPIK_E2E_TESTS_PROJECT_NAME: Final[str] = "e2e-tests"
ATTACHMENT_FILE_SIZE = 2 * 1024 * 1024


@pytest.fixture(autouse=True)
def _fast_llm_judge_reasoning_effort(monkeypatch):
    """Force LLMJudge's default reasoning_effort to "minimal" for the e2e
    suite so LLM-bound assertion runs (test_test_suite, etc.) don't burn
    time on reasoning tokens. Production default stays "low"."""
    monkeypatch.setattr(llm_judge_config, "DEFAULT_REASONING_EFFORT", "minimal")


@pytest.fixture()
def configure_e2e_tests_env():
    with testlib.patch_environ({"OPIK_PROJECT_NAME": OPIK_E2E_TESTS_PROJECT_NAME}):
        yield


@pytest.fixture()
def opik_client(configure_e2e_tests_env, shutdown_cached_client_after_test):
    opik_client_ = opik.api_objects.opik_client.Opik(batching=True)

    yield opik_client_

    # Tests explicitly poll the backend for anything they care about during
    # the call phase, so teardown doesn't need to wait for the upload/flush
    # pipeline to drain. Skip `flush=True` to avoid the 5-s polling budget
    # in `file_upload_manager.flush`.
    opik_client_.end(flush=False)


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
    """A unique project name for the test; the project is deleted on teardown.

    Tolerant of projects that were never created (e.g. test bailed before
    creating one) or already deleted — cleanup is best-effort."""
    name = f"e2e-tests-temporary-project-{random_chars()}"
    yield name
    try:
        project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
        opik_client.rest_client.projects.delete_project_by_id(project_id)
    except rest_api_core.ApiError:
        pass


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
