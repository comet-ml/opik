import collections

import box
import pytest
from testix import *

from comet_llm import config


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(config, "comet_ml")
    patch_module(config, "comet_api_key")
    patch_module(config, "config_helper")



@pytest.fixture(autouse=True)
def mock_config_dict(patch_module):
    patch_module(config, "_COMET_LLM_CONFIG", collections.defaultdict(lambda: None))


def test_init__happyflow(mock_config_dict):
    with Scenario() as s:
        s.comet_ml.login(
            api_key="api-key",
            workspace="the-workspace",
            project_name="the-project"
        )
        # Config object is recreated to re-read the config files
        s.config_helper.create_config_instance() >> "new-config"

        config.init(
            api_key="api-key",
            workspace="the-workspace",
            project="the-project"
        )
        assert config._COMET_LLM_CONFIG == "new-config"

def test_init__not_set_arguments_not_passed_to_comet_ml_init(mock_config_dict):
    with Scenario() as s:
        s.comet_ml.login(api_key="api-key")
        # Config object is recreated to re-read the config files
        s.config_helper.create_config_instance() >> "new-config"

        config.init(api_key="api-key")
        assert config._COMET_LLM_CONFIG == "new-config"


def test_setup_comet_url_override__api_key_contains_url__url_is_not_set_in_config__url_from_api_key_set_in_config(mock_config_dict):
    URL_FROM_API_KEY = "https://www.from.api.key.comet.com"

    with Scenario() as s:
        assert config.comet_url() == config.DEFAULT_COMET_BASE_URL

        s.comet_api_key.parse_api_key("api-key") >> box.Box(base_url=URL_FROM_API_KEY)
        config.setup_comet_url("api-key")

        assert config.comet_url() == URL_FROM_API_KEY


def test_setup_comet_url_override__api_key_contains_url__url_is_set_in_config__url_in_config_remains_the_same(mock_config_dict):
    URL_FROM_API_KEY = "https://www.from.api.key.comet.com"
    URL_FROM_CONFIG = "https://www.from.config.comet.com"
    config._COMET_LLM_CONFIG["comet.url_override"] = URL_FROM_CONFIG

    with Scenario() as s:
        assert config.comet_url() == URL_FROM_CONFIG

        s.comet_api_key.parse_api_key("api-key") >> box.Box(base_url=URL_FROM_API_KEY)
        config.setup_comet_url("api-key")

        assert config.comet_url() == URL_FROM_CONFIG


def test_setup_comet_url_override__api_key_doesnt_contain_url__default_one_is_used(mock_config_dict):
    with Scenario() as s:
        assert config.comet_url() == config.DEFAULT_COMET_BASE_URL

        s.comet_api_key.parse_api_key("api-key") >> None
        config.setup_comet_url("api-key")

        assert config.comet_url() == config.DEFAULT_COMET_BASE_URL
