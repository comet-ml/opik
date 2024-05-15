import comet_ml

import pytest


@pytest.fixture(scope="session")
def comet_api():
    api = comet_ml.API(cache=False)
    return api
