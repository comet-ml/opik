import pytest
from testix import *

from comet_llm import experiment_info, exceptions

@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(experiment_info, "config")


def test_get__api_key_not_specified_and_not_found_in_config__exception_raised():
    with Scenario() as s:
        s.config.api_key() >> None

        with pytest.raises(exceptions.CometAPIKeyIsMissing, match="error-message"):
            experiment_info.get(
                None,
                "the-workspace",
                "project-name",
                api_key_not_found_message="error-message"
            )


def test_get__everything_specified__config_functions_not_called():
    assert experiment_info.get(
        "api-key",
        "the-workspace",
        "project-name",
        api_key_not_found_message="error-message"
    ) == experiment_info.ExperimentInfo(
        "api-key",
        "the-workspace",
        "project-name"
    )


def test_get__nothing_specified__everything_is_taken_from_config():
    with Scenario() as s:
        s.config.api_key() >> "api-key"
        s.config.workspace() >> "the-workspace"
        s.config.project_name() >> "project-name"

        assert experiment_info.get(
            None,
            None,
            None,
            api_key_not_found_message="error-message"
        ) == experiment_info.ExperimentInfo(
            "api-key",
            "the-workspace",
            "project-name"
        )