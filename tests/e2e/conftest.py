import os
import sys
import comet_ml

import pytest


@pytest.fixture(autouse=True, scope="session")
def project():
    os.environ["COMET_PROJECT_NAME"] = f"llm-e2e-tests-setup-py{sys.version_info[2:]}"


@pytest.fixture(scope="session")
def comet_api(project):
    api = comet_ml.API(cache=False)
    return api
