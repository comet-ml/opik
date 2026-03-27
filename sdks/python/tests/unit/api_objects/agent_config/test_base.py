import dataclasses
import typing
from typing import Annotated, Optional
from unittest import mock

import pytest

from opik import context_storage
from opik.api_objects.agent_config.base import AgentConfig
from opik.api_objects.agent_config.blueprint import Blueprint
from opik.api_objects.agent_config.cache import get_global_registry, get_cached_config
from opik.api_objects.agent_config.context import agent_config_context
from opik.api_objects.span import span_data as span_data_mod
from opik.exceptions import AgentConfigNotFound, OpikException
from opik.rest_api import core as rest_api_core
from opik.rest_api.errors import ConflictError
from opik.rest_api.core.request_options import RequestOptions
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.rest_api.types.agent_config_value_public import AgentConfigValuePublic


# ---------------------------------------------------------------------------
# Base class tests
# ---------------------------------------------------------------------------


class TestAgentConfigBaseClass:
    def test_subclass__auto_converted_to_dataclass(self):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        assert dataclasses.is_dataclass(MyConfig)

    def test_subclass__instance_fields_accessible(self):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        instance = MyConfig(temp=0.8, name="agent")
        assert instance.temp == 0.8
        assert instance.name == "agent"

    def test_subclass__opik_fields_populated(self):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        assert "temp" in MyConfig.__field_metadata__
        assert "name" in MyConfig.__field_metadata__
        assert MyConfig.__field_metadata__["temp"].prefixed_key == "MyConfig.temp"
        assert MyConfig.__field_metadata__["name"].prefixed_key == "MyConfig.name"

    def test_subclass__default_value__raises_type_error(self):
        with pytest.raises(TypeError, match="does not support default values"):

            class BadConfig(AgentConfig):
                temp: float = 0.5

    def test_subclass__default_factory__raises_type_error(self):
        with pytest.raises(TypeError, match="does not support default values"):

            class BadConfig(AgentConfig):
                items: list = dataclasses.field(default_factory=list)

    def test_subclass__annotated_types__description_extracted(self):
        class MyConfig(AgentConfig):
            temp: Annotated[float, "Sampling temperature"]
            name: str

        assert MyConfig.__field_metadata__["temp"].description == "Sampling temperature"
        assert MyConfig.__field_metadata__["name"].description is None

    def test_subclass__annotated_with_non_str_metadata__no_description(self):
        class MyConfig(AgentConfig):
            temp: Annotated[float, 42]

        assert MyConfig.__field_metadata__["temp"].description is None

    def test_subclass__optional_type__unwrapped(self):
        class MyConfig(AgentConfig):
            temp: Optional[float]

        cf = MyConfig.__field_metadata__["temp"]
        assert cf.py_type is float

    def test_subclass__isinstance_check(self):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert isinstance(cfg, AgentConfig)
        assert isinstance(cfg, MyConfig)

    def test_base_class__has_no_opik_fields(self):
        assert (
            not hasattr(AgentConfig, "__field_metadata__")
            or AgentConfig.__field_metadata__ == {}
        )


# ---------------------------------------------------------------------------
# create_agent_config_version tests
# ---------------------------------------------------------------------------


class TestCreateAgentConfigVersion:
    def test_first_call__creates_blueprint_and_returns_version_name__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float
            model_name: str

        cfg = MyConfig(temp=0.7, model_name="gpt-4")

        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                    AgentConfigValuePublic(
                        key="MyConfig.model_name", type="string", value="gpt-4"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.create_agent_config.assert_called_once()
        assert result == "v1"

    def test_same_values__no_op_returns_existing_name__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result == "v1"

    def test_same_values_but_blueprint_has_extra_class_keys__no_op__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                    # Keys from a different config class stored in the same project
                    AgentConfigValuePublic(
                        key="OtherConfig.some_field", type="string", value="hello"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result == "v1"

    def test_local_field_removed_but_remaining_values_match__no_op__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float  # model field was removed locally

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                    # model was in a prior version but removed from the local class
                    AgentConfigValuePublic(
                        key="MyConfig.model", type="string", value="gpt-4"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result == "v1"

    def test_different_values__updates_existing_config__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.9)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-2",
                name="v2",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.9"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.update_agent_config.assert_called_once()
        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result == "v2"

    def test_subsequent_blueprint__not_auto_tagged_as_prod(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.9)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-2",
                name="v2",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.9"
                    ),
                ],
            )
        )

        mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.create_or_update_envs.assert_not_called()

    def test_race_condition_409__parallel_create__values_match__reuses_existing(
        self, mock_rest_client, mock_opik_client
    ):
        """POST returns 409 because a parallel caller already created the config.
        The re-fetched blueprint matches local values → no update, reuse existing."""

        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.create_agent_config.side_effect = ConflictError(
            body="config already exists"
        )
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.update_agent_config.assert_not_called()
        assert result == "v1"

    def test_race_condition_409__parallel_create__values_differ__updates(
        self, mock_rest_client, mock_opik_client
    ):
        """POST returns 409 because a parallel caller already created the config
        with different values → update_agent_config (PATCH) is called."""

        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.9)

        mock_rest_client.agent_configs.create_agent_config.side_effect = ConflictError(
            body="config already exists"
        )
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-2",
                name="v2",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.9"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.update_agent_config.assert_called_once()
        assert result == "v2"

    def test_non_agentconfig__raises_type_error(self, mock_opik_client):
        with pytest.raises(TypeError, match="AgentConfig subclass"):
            mock_opik_client.create_agent_config_version("not a config")

    def test_base_agentconfig__raises_type_error(self, mock_opik_client):
        with pytest.raises(TypeError, match="AgentConfig subclass"):
            mock_opik_client.create_agent_config_version(
                AgentConfig.__new__(AgentConfig)
            )


# ---------------------------------------------------------------------------
# get_agent_config tests
# ---------------------------------------------------------------------------


class TestGetAgentConfig:
    def test_no_backend_config__raises_agent_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(AgentConfigNotFound):
            mock_opik_client.get_agent_config(fallback=fallback)

    def test_backend_values__returned_in_result__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
                AgentConfigValuePublic(
                    key="MyConfig.name", type="string", value="backend-model"
                ),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.temp == pytest.approx(0.9)
        assert result.name == "backend-model"

    def test_return_type__isinstance_of_user_class__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.8"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert isinstance(result, MyConfig)
        assert isinstance(result, AgentConfig)

    def test_latest_flag__fetches_latest__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-latest",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.8"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback, latest=True)

        mock_rest_client.agent_configs.get_latest_blueprint.assert_called()
        assert result.temp == pytest.approx(0.8)

    def test_version_param__fetches_by_name__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-v2",
            name="v2",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.3"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_name.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback, version="v2")

        mock_rest_client.agent_configs.get_blueprint_by_name.assert_called_once_with(
            project_id="proj-1",
            name="v2",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.temp == pytest.approx(0.3)

    def test_env_param__fetches_by_env__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-staging",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.6"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback, env="staging")

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="staging",
            project_id="proj-1",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.temp == pytest.approx(0.6)

    def test_backend_explicit_none__preserved_not_overridden_by_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: Optional[float]

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value=None),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback, latest=True)

        assert result.temp is None

    def test_non_agentconfig__raises_type_error(self, mock_opik_client):
        with pytest.raises(TypeError, match="AgentConfig subclass"):
            mock_opik_client.get_agent_config(fallback="not a config")

    def test_multiple_selectors__raises_value_error(self, mock_opik_client):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(ValueError, match="exactly one"):
            mock_opik_client.get_agent_config(
                fallback=fallback, latest=True, version="v1"
            )

        with pytest.raises(ValueError, match="exactly one"):
            mock_opik_client.get_agent_config(
                fallback=fallback, latest=True, env="prod"
            )

        with pytest.raises(ValueError, match="exactly one"):
            mock_opik_client.get_agent_config(
                fallback=fallback, version="v1", env="staging"
            )

    def test_timeout_in_seconds__http_timeout__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = Exception(
            "timeout"
        )

        result = mock_opik_client.get_agent_config(
            fallback=fallback, timeout_in_seconds=5
        )

        assert result is fallback

    def test_timeout_in_seconds__passed_as_request_options(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        mock_opik_client.get_agent_config(fallback=fallback, timeout_in_seconds=3)

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=3),
        )

    def test_timeout_in_seconds__none__request_options_not_set(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_agent_config(
            fallback=fallback, timeout_in_seconds=None
        )

        assert result.temp == pytest.approx(0.9)
        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id=None,
            request_options=None,
        )

    def test_blueprint_missing_field__raises_key_error(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        # Config version only has "temp", "name" is absent.
        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v3",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        with pytest.raises(KeyError, match="v3"):
            mock_opik_client.get_agent_config(fallback=fallback, latest=True)

    def test_blueprint_missing_field__error_names_missing_fields(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v3",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        with pytest.raises(KeyError, match="MyConfig.name"):
            mock_opik_client.get_agent_config(fallback=fallback, latest=True)

    def test_blueprint_missing_all_fields__raises_key_error_listing_all(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        # Config version has no values at all.
        bp = AgentBlueprintPublic(id="bp-1", name="v1", type="blueprint", values=[])
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        with pytest.raises(KeyError, match="v1"):
            mock_opik_client.get_agent_config(fallback=fallback, latest=True)

    def test_blueprint_missing_field__fallback_not_used(
        self, mock_rest_client, mock_opik_client
    ):
        """Fallback values must NOT silently fill in missing config version fields."""

        class MyConfig(AgentConfig):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v2",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        # Must raise, not return a result with fallback.name == "default"
        with pytest.raises(KeyError):
            mock_opik_client.get_agent_config(fallback=fallback, latest=True)


# ---------------------------------------------------------------------------
# Live instance tests
# ---------------------------------------------------------------------------


class TestLiveInstance:
    def test_live_instance__reads_from_cache__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        live = mock_opik_client.get_agent_config(fallback=fallback, latest=True)

        assert live.temp == pytest.approx(0.9)
        assert isinstance(live, MyConfig)
        assert isinstance(live, AgentConfig)

    def test_plain_instance__no_cache_intercept(self):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert cfg.temp == 0.5
        assert cfg._state.project is None


class TestEnvsAndIsFallback:
    def test_plain_instance__is_fallback_true(self):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert cfg.is_fallback is True

    def test_plain_instance__envs_is_none(self):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert cfg.envs is None

    def test_no_backend_config__raises_agent_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(AgentConfigNotFound):
            mock_opik_client.get_agent_config(fallback=fallback)

    def test_backend_config__is_fallback_false(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.is_fallback is False

    def test_backend_config__envs_populated(self, mock_rest_client, mock_opik_client):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            envs=["prod", "STAGING"],
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.envs == ["prod", "STAGING"]

    def test_backend_config__no_envs__envs_is_none(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.envs is None

    def test_create_version__sets_is_fallback_false(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )

        mock_opik_client.create_agent_config_version(cfg)

        assert cfg.is_fallback is False

    def test_is_fallback__resets_to_true_when_cache_cleared(
        self, mock_rest_client, mock_opik_client
    ):
        """is_fallback becomes True on next field access when cache is cleared."""

        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        live = mock_opik_client.get_agent_config(fallback=fallback, latest=True)
        assert live.is_fallback is False

        get_global_registry().clear()
        _ = live.temp  # triggers _resolve_field against empty cache

        assert live.is_fallback is True

    def test_is_fallback__resets_to_false_when_cache_restored(
        self, mock_rest_client, mock_opik_client
    ):
        """is_fallback resets to False on next field access after cache is repopulated."""

        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        live = mock_opik_client.get_agent_config(fallback=fallback, latest=True)
        get_global_registry().clear()
        _ = live.temp  # cache is empty → is_fallback=True
        assert live.is_fallback is True

        # Simulate connection restored: cache repopulated by background refresh
        cache = get_cached_config("test-project", None, None)
        cache.update(Blueprint(bp))

        _ = live.temp  # cache now has a blueprint → is_fallback=False
        assert live.is_fallback is False

    def test_create_version__existing_match__sets_envs_from_blueprint(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                envs=["prod"],
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp", type="float", value="0.7"
                    ),
                ],
            )
        )

        mock_opik_client.create_agent_config_version(cfg)

        assert cfg.envs == ["prod"]


# ---------------------------------------------------------------------------
# Metadata injection tests
# ---------------------------------------------------------------------------


def _make_live_instance(mock_rest_client, mock_opik_client, fallback, bp):
    """Helper: resolve a live AgentConfig instance from a mocked blueprint."""
    mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
    mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
    mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp
    return mock_opik_client.get_agent_config(fallback=fallback, latest=True)


class TestMetadataInjection:
    def test_field_access__injects_trace_and_span_metadata(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v3",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.7"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(temp=0.0), bp
        )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch("opik.opik_context.update_current_span") as mock_span,
        ):
            _ = live.temp

        for mock_call in (mock_trace, mock_span):
            mock_call.assert_called_once()
            payload = mock_call.call_args.kwargs["metadata"]
            assert "agent_configuration" in payload
            assert payload["agent_configuration"]["_blueprint_id"] == "bp-1"
            assert payload["agent_configuration"]["blueprint_version"] == "v3"
            assert "MyConfig.temp" in payload["agent_configuration"]["values"]

    def test_no_active_trace_or_span__no_exception(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.5"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(temp=0.0), bp
        )

        with (
            mock.patch(
                "opik.opik_context.update_current_trace",
                side_effect=OpikException("no trace"),
            ),
            mock.patch(
                "opik.opik_context.update_current_span",
                side_effect=OpikException("no span"),
            ),
        ):
            value = live.temp  # must not raise

        assert value == pytest.approx(0.5)

    def test_active_trace_but_no_span__trace_updated_span_skipped(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.3"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(temp=0.0), bp
        )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch(
                "opik.opik_context.update_current_span",
                side_effect=OpikException("no span"),
            ),
        ):
            _ = live.temp

        mock_trace.assert_called_once()

    def test_metadata_keys__blueprint_id_prefixed_and_version_from_name(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            lr: float

        bp = AgentBlueprintPublic(
            id="bp-42",
            name="v7",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.lr", type="float", value="0.01"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(lr=0.0), bp
        )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch("opik.opik_context.update_current_span"),
        ):
            _ = live.lr

        ac = mock_trace.call_args.kwargs["metadata"]["agent_configuration"]
        assert "blueprint_id" not in ac
        assert ac["_blueprint_id"] == "bp-42"
        assert ac["blueprint_version"] == "v7"

    def test_metadata__blueprint_version_none_when_name_absent(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            lr: float

        bp = AgentBlueprintPublic(
            id="bp-99",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.lr", type="float", value="0.5"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(lr=0.0), bp
        )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch("opik.opik_context.update_current_span"),
        ):
            _ = live.lr

        ac = mock_trace.call_args.kwargs["metadata"]["agent_configuration"]
        assert ac["blueprint_version"] is None


# ---------------------------------------------------------------------------
# Mask with env resolution tests
# ---------------------------------------------------------------------------


class TestGetAgentConfigWithMask:
    def test_mask_active__passed_to_get_blueprint_by_env(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            greeting: str

        fallback = MyConfig(greeting="default")

        bp = AgentBlueprintPublic(
            id="bp-masked",
            type="blueprint",
            values=[
                AgentConfigValuePublic(
                    key="MyConfig.greeting", type="string", value="custom"
                ),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        with agent_config_context("mask-abc"):
            result = mock_opik_client.get_agent_config(fallback=fallback)

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id="mask-abc",
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.greeting == "custom"

    def test_mask_active__masked_value_overrides_base(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            greeting: str

        fallback = MyConfig(greeting="default")

        masked_bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(
                    key="MyConfig.greeting", type="string", value="custom-greeting"
                ),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = masked_bp

        with agent_config_context("mask-xyz"):
            result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.greeting == "custom-greeting"

    def test_no_mask__mask_id_none_in_request(self, mock_rest_client, mock_opik_client):
        class MyConfig(AgentConfig):
            greeting: str

        fallback = MyConfig(greeting="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(
                    key="MyConfig.greeting", type="string", value="prod-greeting"
                ),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_agent_config(fallback=fallback)

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-1",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.greeting == "prod-greeting"

    def test_no_prod_env__raises_agent_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            greeting: str

        fallback = MyConfig(greeting="default")

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        # prod env returns 404 (default in conftest)

        with agent_config_context("mask-abc"):
            with pytest.raises(AgentConfigNotFound, match="env='prod'"):
                mock_opik_client.get_agent_config(fallback=fallback)


# ---------------------------------------------------------------------------
# Fallback on unexpected backend errors
# ---------------------------------------------------------------------------


class TestGetAgentConfigFallbackOnError:
    def test_server_error_on_env_lookup__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            rest_api_core.ApiError(status_code=500, body="internal server error")
        )

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.temp == 0.5
        assert result.is_fallback is True

    def test_server_error_on_latest_lookup__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = (
            rest_api_core.ApiError(status_code=503, body="service unavailable")
        )

        result = mock_opik_client.get_agent_config(fallback=fallback, latest=True)

        assert result.temp == 0.5
        assert result.is_fallback is True

    def test_network_error_on_env_lookup__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            ConnectionError("network unreachable")
        )

        result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.temp == 0.5
        assert result.is_fallback is True


# ---------------------------------------------------------------------------
# @track context guard tests
# ---------------------------------------------------------------------------


class TestTrackContextGuard:
    @pytest.fixture(autouse=True)
    def fake_track_context(self):
        """Override the module-level autouse fixture: no trace context for these tests."""
        yield

    def test_outside_track_context__raises_runtime_error(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(RuntimeError, match="@opik.track"):
            mock_opik_client.get_agent_config(fallback=fallback)

    def test_inside_track_context_via_span__succeeds(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="MyConfig.temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        span = span_data_mod.SpanData(trace_id="fake-trace", name="test-span")
        with context_storage.temporary_context(span, trace_data=None):
            result = mock_opik_client.get_agent_config(fallback=fallback)

        assert result.temp == pytest.approx(0.9)

    def test_outside_track_context__error_message_mentions_api_methods(
        self, mock_rest_client, mock_opik_client
    ):
        class MySpecialConfig(AgentConfig):
            temp: float

        fallback = MySpecialConfig(temp=0.5)

        with pytest.raises(RuntimeError, match="get_agent_config"):
            mock_opik_client.get_agent_config(fallback=fallback)

    def test_creating_fallback_instance_outside_track__no_error(self):
        """Plain AgentConfig instantiation (for use as fallback) must not require @track."""

        class MyConfig(AgentConfig):
            temp: float

        # Should not raise even when no trace context is present
        cfg = MyConfig(temp=0.5)
        assert cfg.temp == 0.5


# ---------------------------------------------------------------------------
# _matches_blueprint description tests
# ---------------------------------------------------------------------------


class TestMatchesBlueprintDescription:
    def _make_blueprint(
        self,
        key: str,
        value: str,
        type_: str,
        description: typing.Optional[str] = None,
    ) -> Blueprint:
        return Blueprint(
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key=key, type=type_, value=value, description=description
                    )
                ],
            )
        )

    def test_same_value_same_description__matches(self):
        class MyConfig(AgentConfig):
            temp: Annotated[float, "Sampling temperature"]

        cfg = MyConfig(temp=0.7)
        bp = self._make_blueprint(
            "MyConfig.temp", "0.7", "float", "Sampling temperature"
        )
        assert cfg._matches_blueprint(bp, cfg._extract_fields_with_values()) is True

    def test_same_value_changed_description__does_not_match(self):
        class MyConfig(AgentConfig):
            temp: Annotated[float, "New description"]

        cfg = MyConfig(temp=0.7)
        bp = self._make_blueprint("MyConfig.temp", "0.7", "float", "Old description")
        assert cfg._matches_blueprint(bp, cfg._extract_fields_with_values()) is False

    def test_same_value_description_added_where_none_was__does_not_match(self):
        class MyConfig(AgentConfig):
            temp: Annotated[float, "Added description"]

        cfg = MyConfig(temp=0.7)
        bp = self._make_blueprint("MyConfig.temp", "0.7", "float", description=None)
        assert cfg._matches_blueprint(bp, cfg._extract_fields_with_values()) is False

    def test_same_value_description_removed__does_not_match(self):
        class MyConfig(AgentConfig):
            temp: float  # no description

        cfg = MyConfig(temp=0.7)
        bp = self._make_blueprint("MyConfig.temp", "0.7", "float", "Old description")
        assert cfg._matches_blueprint(bp, cfg._extract_fields_with_values()) is False

    def test_same_value_both_no_description__matches(self):
        class MyConfig(AgentConfig):
            temp: float

        cfg = MyConfig(temp=0.7)
        bp = self._make_blueprint("MyConfig.temp", "0.7", "float", description=None)
        assert cfg._matches_blueprint(bp, cfg._extract_fields_with_values()) is True

    def test_description_change_triggers_update_via_create_version(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(AgentConfig):
            temp: Annotated[float, "New description"]

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp",
                        type="float",
                        value="0.7",
                        description="Old description",
                    )
                ],
            )
        )
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-2",
                name="v2",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(
                        key="MyConfig.temp",
                        type="float",
                        value="0.7",
                        description="New description",
                    )
                ],
            )
        )

        result = mock_opik_client.create_agent_config_version(cfg)

        mock_rest_client.agent_configs.update_agent_config.assert_called_once()
        assert result == "v2"
