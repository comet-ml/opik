import box
import pytest
from testix import *

from comet_llm import api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(api, "LOGGER")
    patch_module(api, "io")
    patch_module(api, "experiment_info")
    patch_module(api, "ExperimentAPI")


def test_log_user_feedback__happyflow():
    MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to log_user_feedback or as an environment
    variable named COMET_API_KEY
    """

    with Scenario() as s:
        s.experiment_info.get(
            "api-key",
            api_key_not_found_message=MESSAGE,
        )>> box.Box(api_key="api-key")

        s.ExperimentAPI.from_existing_id("experiment-key", "api-key") >> Fake("api_experiment")

        s.api_experiment.log_metric("user_feedback", 1)

        api.log_user_feedback(
            id="experiment-key",
            score=1,
            api_key="api-key"
        )


def test_log_user_feedback__non_allowed_score__error_logged():
    with Scenario() as s:
        s.LOGGER.error("Score can only be 0 or 1 when calling 'log_user_feedback'")
        api.log_user_feedback(
            id="experiment-key",
            score=5,
        )