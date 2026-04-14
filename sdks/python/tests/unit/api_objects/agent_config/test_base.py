import dataclasses
import typing
from unittest import mock

import pytest

from opik import context_storage
from opik.api_objects.agent_config.base import Config
from opik.api_objects.agent_config.blueprint import Blueprint
from opik.api_objects.agent_config.cache import get_global_registry, get_cached_config
from opik.api_objects.agent_config.context import agent_config_context
from opik.api_objects.span import span_data as span_data_mod
from opik.exceptions import ConfigNotFound, ConfigMismatch, OpikException
from opik.rest_api import core as rest_api_core
from opik.rest_api.core.request_options import RequestOptions
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.rest_api.types.agent_config_value_public import AgentConfigValuePublic


# ---------------------------------------------------------------------------
# Base class tests
# ---------------------------------------------------------------------------


class TestConfigBaseClass:
    def test_subclass__auto_converted_to_dataclass(self):
        class MyConfig(Config):
            temp: float
            name: str

        assert dataclasses.is_dataclass(MyConfig)

    def test_subclass__instance_fields_accessible(self):
        class MyConfig(Config):
            temp: float
            name: str

        instance = MyConfig(temp=0.8, name="agent")
        assert instance.temp == 0.8
        assert instance.name == "agent"

    def test_subclass__field_names_populated(self):
        class MyConfig(Config):
            temp: float
            name: str

        assert "temp" in MyConfig.__field_names__
        assert "name" in MyConfig.__field_names__
        assert MyConfig.__field_names__ == ("temp", "name")

    def test_subclass__default_value__usable_without_passing_arg(self):
        class MyConfig(Config):
            temp: float = 0.5
            model: str = "gpt-4"

        cfg = MyConfig()
        assert cfg.temp == 0.5
        assert cfg.model == "gpt-4"

    def test_subclass__default_factory__usable_without_passing_arg(self):
        class MyConfig(Config):
            items: list = dataclasses.field(default_factory=list)

        cfg = MyConfig()
        assert cfg.items == []

    def test_subclass__isinstance_check(self):
        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert isinstance(cfg, Config)
        assert isinstance(cfg, MyConfig)

    def test_base_class__has_empty_field_names(self):
        assert Config.__field_names__ == ()


# ---------------------------------------------------------------------------
# get_or_create_config tests
# ---------------------------------------------------------------------------


class TestGetOrCreateConfig:
    def test_explicit_env_selector_with_no_matching_blueprint__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(ConfigNotFound):
            mock_opik_client.get_or_create_config(fallback=fallback, env="staging")

    def test_backend_values__returned_in_result__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
                AgentConfigValuePublic(
                    key="name", type="string", value="backend-model"
                ),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback)

        assert result.temp == pytest.approx(0.9)
        assert result.name == "backend-model"

    def test_return_type__isinstance_of_user_class__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.8"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback)

        assert isinstance(result, MyConfig)
        assert isinstance(result, Config)

    def test_version_param__fetches_by_name__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-v2",
            name="v2",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.3"),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_name.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback, version="v2")

        mock_rest_client.agent_configs.get_blueprint_by_name.assert_called_once_with(
            project_id="proj-test",
            name="v2",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.temp == pytest.approx(0.3)

    def test_env_param__fetches_by_env__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-staging",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.6"),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback, env="staging")

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="staging",
            project_id="proj-test",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.temp == pytest.approx(0.6)

    def test_non_config__raises_type_error(self, mock_opik_client):
        with pytest.raises(TypeError, match="Config subclass"):
            mock_opik_client.get_or_create_config(fallback="not a config")

    def test_multiple_selectors__raises_value_error(self, mock_opik_client):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(ValueError, match="at most one"):
            mock_opik_client.get_or_create_config(
                fallback=fallback, version="v1", env="prod"
            )

    def test_timeout_in_seconds__http_timeout__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = Exception(
            "timeout"
        )

        result = mock_opik_client.get_or_create_config(
            fallback=fallback, timeout_in_seconds=5
        )

        assert result is fallback

    def test_timeout_in_seconds__passed_as_request_options(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        mock_opik_client.get_or_create_config(
            fallback=fallback, env="prod", timeout_in_seconds=3
        )

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-test",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=3),
        )

    def test_timeout_in_seconds__none__request_options_not_set(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_or_create_config(
            fallback=fallback, env="prod", timeout_in_seconds=None
        )

        assert result.temp == pytest.approx(0.9)
        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-test",
            mask_id=None,
            request_options=None,
        )

    def test_no_selector_no_backend__auto_creates_from_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        """When no env/version given AND no config exists, auto-create from fallback via public API."""

        class MyConfig(Config):
            temp: float
            model: str

        fallback = MyConfig(temp=0.7, model="gpt-4")

        # Default fixture: get_latest_blueprint raises 404, so bp is None.
        # With no selector, _get_or_create_from_backend should auto-create
        # via manager.create_blueprint → rest_client.agent_configs.create_agent_config.
        created_raw = AgentBlueprintPublic(
            id="bp-new",
            name="v1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                AgentConfigValuePublic(key="model", type="string", value="gpt-4"),
            ],
        )
        mock_rest_client.agent_configs.create_agent_config.return_value = None
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = created_raw

        result = mock_opik_client.get_or_create_config(fallback=fallback)

        mock_rest_client.agent_configs.create_agent_config.assert_called_once()
        assert isinstance(result, MyConfig)
        assert result.temp == pytest.approx(0.7)
        assert result.model == "gpt-4"
        assert result.is_fallback is False

    def test_no_selector_with_prod_blueprint__returns_prod_values(
        self, mock_rest_client, mock_opik_client
    ):
        """Default (no env/version): resolves to env="prod" and returns the prod blueprint."""

        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-prod",
            name="v3",
            type="blueprint",
            values=[AgentConfigValuePublic(key="temp", type="float", value="0.42")],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback)

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-test",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result.temp == pytest.approx(0.42)

    def test_no_selector_prod_missing_but_other_configs_exist__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        """Default (env="prod") missing, but other configs exist → ConfigNotFound."""

        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        # prod env lookup returns 404 (default from conftest), but latest returns a bp.
        latest_bp = AgentBlueprintPublic(
            id="bp-latest",
            name="v2",
            type="blueprint",
            values=[AgentConfigValuePublic(key="temp", type="float", value="0.8")],
        )
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = latest_bp

        with pytest.raises(ConfigNotFound, match="env='prod'"):
            mock_opik_client.get_or_create_config(fallback=fallback)

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()

    def test_version_latest__fetches_latest_blueprint(
        self, mock_rest_client, mock_opik_client
    ):
        """version="latest" is a sentinel that fetches the latest blueprint without env default."""

        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-latest",
            name="v3",
            type="blueprint",
            values=[AgentConfigValuePublic(key="temp", type="float", value="0.42")],
        )
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_or_create_config(
            fallback=fallback, version="latest"
        )

        mock_rest_client.agent_configs.get_latest_blueprint.assert_called_once()
        mock_rest_client.agent_configs.get_blueprint_by_env.assert_not_called()
        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result.temp == pytest.approx(0.42)

    def test_version_latest__no_config_exists__auto_creates_from_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        """version="latest" auto-creates from fallback when the project is empty."""

        class MyConfig(Config):
            temp: float
            model: str

        fallback = MyConfig(temp=0.7, model="gpt-4")

        # get_latest_blueprint returns 404 by default (project empty).
        created_raw = AgentBlueprintPublic(
            id="bp-new",
            name="v1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                AgentConfigValuePublic(key="model", type="string", value="gpt-4"),
            ],
        )
        mock_rest_client.agent_configs.create_agent_config.return_value = None
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = created_raw

        result = mock_opik_client.get_or_create_config(
            fallback=fallback, version="latest"
        )

        mock_rest_client.agent_configs.create_agent_config.assert_called_once()
        assert isinstance(result, MyConfig)
        assert result.temp == pytest.approx(0.7)
        assert result.model == "gpt-4"
        assert result.is_fallback is False


# ---------------------------------------------------------------------------
# Config mismatch tests
# ---------------------------------------------------------------------------


class TestConfigMismatch:
    def test_blueprint_missing_field__raises_config_mismatch(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        # Config version only has "temp", "name" is absent.
        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v3",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        with pytest.raises(ConfigMismatch, match="missing expected field"):
            mock_opik_client.get_or_create_config(fallback=fallback)

    def test_blueprint_missing_field__error_names_missing_fields(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v3",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        with pytest.raises(ConfigMismatch, match="name"):
            mock_opik_client.get_or_create_config(fallback=fallback)

    def test_blueprint_missing_all_fields__raises_config_mismatch_listing_all(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(id="bp-1", name="v1", type="blueprint", values=[])
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        with pytest.raises(ConfigMismatch, match="v1"):
            mock_opik_client.get_or_create_config(fallback=fallback, env="prod")

    def test_blueprint_missing_field__fallback_not_used(
        self, mock_rest_client, mock_opik_client
    ):
        """Fallback values must NOT silently fill in missing config version fields."""

        class MyConfig(Config):
            temp: float
            name: str

        fallback = MyConfig(temp=0.5, name="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v2",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        # Must raise, not return a result with fallback.name == "default"
        with pytest.raises(ConfigMismatch):
            mock_opik_client.get_or_create_config(fallback=fallback)


# ---------------------------------------------------------------------------
# Live instance tests
# ---------------------------------------------------------------------------


def _make_live_instance(mock_rest_client, mock_opik_client, fallback, bp):
    """Helper: resolve a live Config instance from a mocked blueprint (via env="prod")."""
    mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
    mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
    mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
    mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
    mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp
    return mock_opik_client.get_or_create_config(fallback=fallback, env="prod")


class TestLiveInstance:
    def test_live_instance__reads_from_cache__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )

        live = _make_live_instance(mock_rest_client, mock_opik_client, fallback, bp)

        assert live.temp == pytest.approx(0.9)
        assert isinstance(live, MyConfig)
        assert isinstance(live, Config)

    def test_plain_instance__no_cache_intercept(self):
        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert cfg.temp == 0.5
        assert cfg._state.project is None


class TestIsFallback:
    def test_plain_instance__is_fallback_true(self):
        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.5)
        assert cfg.is_fallback is True

    def test_env_selector_with_no_backend_config__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(ConfigNotFound):
            mock_opik_client.get_or_create_config(fallback=fallback, env="prod")

    def test_backend_config__is_fallback_false(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback)

        assert result.is_fallback is False

    def test_create_config__sets_is_fallback_false(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                ],
            )
        )

        mock_opik_client.create_config(cfg)

        assert cfg.is_fallback is False

    def test_is_fallback__resets_to_true_when_cache_cleared(
        self, mock_rest_client, mock_opik_client
    ):
        """is_fallback becomes True on next field access when cache is cleared."""

        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )

        live = _make_live_instance(mock_rest_client, mock_opik_client, fallback, bp)
        assert live.is_fallback is False

        get_global_registry().clear()
        _ = live.temp  # triggers _resolve_field against empty cache

        assert live.is_fallback is True

    def test_is_fallback__resets_to_false_when_cache_restored(
        self, mock_rest_client, mock_opik_client
    ):
        """is_fallback resets to False on next field access after cache is repopulated."""

        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )

        live = _make_live_instance(mock_rest_client, mock_opik_client, fallback, bp)
        get_global_registry().clear()
        _ = live.temp  # cache is empty -> is_fallback=True
        assert live.is_fallback is True

        # Simulate connection restored: cache repopulated by background refresh
        cache = get_cached_config("test-project", "prod", None)
        cache.update(Blueprint(bp))

        _ = live.temp  # cache now has a blueprint -> is_fallback=False
        assert live.is_fallback is False


# ---------------------------------------------------------------------------
# Metadata injection tests
# ---------------------------------------------------------------------------


class TestMetadataInjection:
    def test_field_access__injects_trace_and_span_metadata(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v3",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.7"),
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
            assert "temp" in payload["agent_configuration"]["values"]

    def test_no_active_trace_or_span__no_exception(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.5"),
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
        class MyConfig(Config):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.3"),
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
        class MyConfig(Config):
            lr: float

        bp = AgentBlueprintPublic(
            id="bp-42",
            name="v7",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="lr", type="float", value="0.01"),
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
        class MyConfig(Config):
            lr: float

        bp = AgentBlueprintPublic(
            id="bp-99",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="lr", type="float", value="0.5"),
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

    def test_metadata__mask_id_included_when_set(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            greeting: str

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="greeting", type="string", value="hello"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        with agent_config_context("mask-abc"):
            live = mock_opik_client.get_or_create_config(
                fallback=MyConfig(greeting="default"), env="prod"
            )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch("opik.opik_context.update_current_span"),
        ):
            _ = live.greeting

        ac = mock_trace.call_args.kwargs["metadata"]["agent_configuration"]
        assert ac["_mask_id"] == "mask-abc"

    def test_metadata__mask_id_absent_when_none(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            greeting: str

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="greeting", type="string", value="hello"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(greeting="default"), bp
        )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch("opik.opik_context.update_current_span"),
        ):
            _ = live.greeting

        ac = mock_trace.call_args.kwargs["metadata"]["agent_configuration"]
        assert "_mask_id" not in ac

    def test_metadata__unprefixed_field_keys(self, mock_rest_client, mock_opik_client):
        """Field keys in metadata should be unprefixed (e.g. 'temp' not 'MyConfig.temp')."""

        class MyConfig(Config):
            temp: float

        bp = AgentBlueprintPublic(
            id="bp-1",
            name="v1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.7"),
            ],
        )
        live = _make_live_instance(
            mock_rest_client, mock_opik_client, MyConfig(temp=0.0), bp
        )

        with (
            mock.patch("opik.opik_context.update_current_trace") as mock_trace,
            mock.patch("opik.opik_context.update_current_span"),
        ):
            _ = live.temp

        ac = mock_trace.call_args.kwargs["metadata"]["agent_configuration"]
        values = ac["values"]
        assert "temp" in values
        # Make sure no prefixed key exists
        assert not any("." in k for k in values.keys())
        assert values["temp"]["value"] == pytest.approx(0.7)
        assert values["temp"]["type"] == "float"


# ---------------------------------------------------------------------------
# Mask with env resolution tests
# ---------------------------------------------------------------------------


class TestGetOrCreateConfigWithMask:
    def test_mask_active__passed_to_get_blueprint_by_env(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            greeting: str

        fallback = MyConfig(greeting="default")

        bp = AgentBlueprintPublic(
            id="bp-masked",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="greeting", type="string", value="custom"),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        with agent_config_context("mask-abc"):
            result = mock_opik_client.get_or_create_config(
                fallback=fallback, env="prod"
            )

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-test",
            mask_id="mask-abc",
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.greeting == "custom"

    def test_mask_active__masked_value_overrides_base(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            greeting: str

        fallback = MyConfig(greeting="default")

        masked_bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(
                    key="greeting", type="string", value="custom-greeting"
                ),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = masked_bp

        with agent_config_context("mask-xyz"):
            result = mock_opik_client.get_or_create_config(
                fallback=fallback, env="prod"
            )

        assert result.greeting == "custom-greeting"

    def test_no_mask__mask_id_none_in_request(self, mock_rest_client, mock_opik_client):
        class MyConfig(Config):
            greeting: str

        fallback = MyConfig(greeting="default")

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(
                    key="greeting", type="string", value="prod-greeting"
                ),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_or_create_config(fallback=fallback, env="prod")

        mock_rest_client.agent_configs.get_blueprint_by_env.assert_called_once_with(
            env_name="prod",
            project_id="proj-test",
            mask_id=None,
            request_options=RequestOptions(timeout_in_seconds=5),
        )
        assert result.greeting == "prod-greeting"

    def test_no_prod_env__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            greeting: str

        fallback = MyConfig(greeting="default")

        # prod env returns 404 (default in conftest)

        with agent_config_context("mask-abc"):
            with pytest.raises(ConfigNotFound, match="env='prod'"):
                mock_opik_client.get_or_create_config(fallback=fallback, env="prod")

    def test_blueprint_name_override__missing_blueprint__raises_config_not_found_not_auto_create(
        self, mock_rest_client, mock_opik_client
    ):
        """Runner context that pins a specific blueprint name must raise ConfigNotFound
        when the blueprint is absent, even when the call would otherwise auto-create
        (default no-selector path that maps to env='prod' + auto_create_if_empty)."""

        class MyConfig(Config):
            greeting: str

        fallback = MyConfig(greeting="default")

        # Simulate "blueprint does not exist" — ConfigManager.get_blueprint returns
        # None only on 404, so use side_effect rather than return_value=None.
        mock_rest_client.agent_configs.get_blueprint_by_name.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )

        with agent_config_context(mask_id=None, blueprint_name="pinned-v1"):
            with pytest.raises(ConfigNotFound, match="version='pinned-v1'"):
                # No env/version supplied — would normally auto-create from fallback
                # when the project is empty. The context override must suppress that.
                mock_opik_client.get_or_create_config(fallback=fallback)

    def test_blueprint_name_override__latest_path__missing_blueprint__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        """Same guarantee for the version='latest' path (which also sets
        auto_create_if_empty=True before calling _get_or_create_from_backend)."""

        class MyConfig(Config):
            greeting: str

        fallback = MyConfig(greeting="default")

        mock_rest_client.agent_configs.get_blueprint_by_name.side_effect = (
            rest_api_core.ApiError(status_code=404, body="not found")
        )

        with agent_config_context(mask_id=None, blueprint_name="pinned-v1"):
            with pytest.raises(ConfigNotFound, match="version='pinned-v1'"):
                mock_opik_client.get_or_create_config(
                    fallback=fallback, version="latest"
                )


# ---------------------------------------------------------------------------
# Fallback on unexpected backend errors
# ---------------------------------------------------------------------------


class TestGetOrCreateConfigFallbackOnError:
    def test_server_error_on_env_lookup__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            rest_api_core.ApiError(status_code=500, body="internal server error")
        )

        result = mock_opik_client.get_or_create_config(fallback=fallback, env="prod")

        assert result.temp == 0.5
        assert result.is_fallback is True

    def test_network_error_on_env_lookup__returns_fallback(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            ConnectionError("network unreachable")
        )

        result = mock_opik_client.get_or_create_config(fallback=fallback, env="prod")

        assert result.temp == 0.5
        assert result.is_fallback is True


# ---------------------------------------------------------------------------
# get_or_create_config without fallback — returns generic Config
# ---------------------------------------------------------------------------


class TestGetOrCreateConfigWithoutFallback:
    def test_no_fallback__backend_config_exists__returns_generic_config(
        self, mock_rest_client, mock_opik_client
    ):
        """Without a fallback, a successful fetch returns a base ``Config`` instance
        (no typed field access, but ``is_fallback`` is False and state is populated)."""

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
                AgentConfigValuePublic(key="name", type="string", value="backend"),
            ],
        )
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        result = mock_opik_client.get_or_create_config()

        assert isinstance(result, Config)
        assert type(result) is Config
        assert result.is_fallback is False
        assert result.temp == pytest.approx(0.9)
        assert result.name == "backend"

    def test_no_fallback__fetch_error__re_raises(
        self, mock_rest_client, mock_opik_client
    ):
        """Without a fallback, backend errors propagate instead of being swallowed."""
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            ConnectionError("network unreachable")
        )

        with pytest.raises(ConnectionError):
            mock_opik_client.get_or_create_config()

    def test_no_fallback__api_error__re_raises(
        self, mock_rest_client, mock_opik_client
    ):
        """Non-404 ApiError from the backend propagates when no fallback is given."""
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = (
            rest_api_core.ApiError(status_code=500, body="boom")
        )

        with pytest.raises(rest_api_core.ApiError):
            mock_opik_client.get_or_create_config()

    def test_no_fallback__empty_project__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        """When the project has no config and no fallback is given, raise
        ConfigNotFound instead of auto-creating."""
        # Default conftest: get_blueprint_by_env and get_latest_blueprint both return 404.
        with pytest.raises(ConfigNotFound):
            mock_opik_client.get_or_create_config()

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()

    def test_no_fallback__version_latest_empty_project__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        """version='latest' without a fallback raises instead of auto-creating."""
        with pytest.raises(ConfigNotFound):
            mock_opik_client.get_or_create_config(version="latest")

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()

    def test_no_fallback__explicit_env_missing__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        """Explicit env selector that misses still raises ConfigNotFound."""
        with pytest.raises(ConfigNotFound):
            mock_opik_client.get_or_create_config(env="staging")

    def test_no_fallback__prod_missing_but_other_configs_exist__raises_config_not_found(
        self, mock_rest_client, mock_opik_client
    ):
        """Default path (env=prod) with prod missing but other configs present still
        raises even without a fallback."""
        latest_bp = AgentBlueprintPublic(
            id="bp-latest", name="v2", type="blueprint", values=[]
        )
        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = latest_bp

        with pytest.raises(ConfigNotFound, match="env='prod'"):
            mock_opik_client.get_or_create_config()

        mock_rest_client.agent_configs.create_agent_config.assert_not_called()

    def test_base_config_passed_as_fallback__raises_type_error(self, mock_opik_client):
        """Passing a plain ``Config()`` instance as fallback is rejected — fallback
        must be a subclass instance."""
        with pytest.raises(TypeError, match="Config subclass"):
            mock_opik_client.get_or_create_config(fallback=Config())


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
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        with pytest.raises(RuntimeError, match="@opik.track"):
            mock_opik_client.get_or_create_config(fallback=fallback)

    def test_inside_track_context_via_span__succeeds(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        fallback = MyConfig(temp=0.5)

        bp = AgentBlueprintPublic(
            id="bp-1",
            type="blueprint",
            values=[
                AgentConfigValuePublic(key="temp", type="float", value="0.9"),
            ],
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.agent_configs.get_blueprint_by_env.side_effect = None
        mock_rest_client.agent_configs.get_blueprint_by_env.return_value = bp

        span = span_data_mod.SpanData(trace_id="fake-trace", name="test-span")
        with context_storage.temporary_context(span, trace_data=None):
            result = mock_opik_client.get_or_create_config(
                fallback=fallback, env="prod"
            )

        assert result.temp == pytest.approx(0.9)

    def test_outside_track_context__error_message_mentions_get_or_create_config(
        self, mock_rest_client, mock_opik_client
    ):
        class MySpecialConfig(Config):
            temp: float

        fallback = MySpecialConfig(temp=0.5)

        with pytest.raises(RuntimeError, match="get_or_create_config"):
            mock_opik_client.get_or_create_config(fallback=fallback)

    def test_creating_fallback_instance_outside_track__no_error(self):
        """Plain Config instantiation (for use as fallback) must not require @track."""

        class MyConfig(Config):
            temp: float

        # Should not raise even when no trace context is present
        cfg = MyConfig(temp=0.5)
        assert cfg.temp == 0.5


# ---------------------------------------------------------------------------
# create_config tests
# ---------------------------------------------------------------------------


class TestCreateConfig:
    def test_first_call__creates_blueprint_and_returns_version_name__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float
            model_name: str

        cfg = MyConfig(temp=0.7, model_name="gpt-4")

        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                    AgentConfigValuePublic(
                        key="model_name", type="string", value="gpt-4"
                    ),
                ],
            )
        )

        result = mock_opik_client.create_config(cfg)

        mock_rest_client.agent_configs.create_agent_config.assert_called_once()
        assert result == "v1"

    def test_latest_exists__updates_existing_config__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.9)

        mock_rest_client.agent_configs.get_latest_blueprint.side_effect = None
        mock_rest_client.agent_configs.get_latest_blueprint.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                ],
            )
        )
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-2",
                name="v2",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(key="temp", type="float", value="0.9"),
                ],
            )
        )

        result = mock_opik_client.create_config(cfg)

        mock_rest_client.agent_configs.update_agent_config.assert_called_once()
        mock_rest_client.agent_configs.create_agent_config.assert_not_called()
        assert result == "v2"

    def test_race_condition_409__create_fails_then_updates(
        self, mock_rest_client, mock_opik_client
    ):
        """POST returns 409 because a parallel caller already created the config.
        Falls through to update_agent_config path."""

        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.create_agent_config.side_effect = (
            rest_api_core.ApiError(status_code=409, body="conflict")
        )
        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-2",
                name="v2",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                ],
            )
        )

        result = mock_opik_client.create_config(cfg)

        mock_rest_client.agent_configs.update_agent_config.assert_called_once()
        assert result == "v2"

    def test_non_config__raises_type_error(self, mock_opik_client):
        with pytest.raises(TypeError, match="Config subclass"):
            mock_opik_client.create_config("not a config")

    def test_base_config__raises_type_error(self, mock_opik_client):
        with pytest.raises(TypeError, match="Config subclass"):
            mock_opik_client.create_config(Config.__new__(Config))

    def test_does_not_require_track_context(self, mock_rest_client, mock_opik_client):
        """create_config should work without @opik.track context."""

        class MyConfig(Config):
            temp: float

        cfg = MyConfig(temp=0.7)

        mock_rest_client.agent_configs.get_blueprint_by_id.return_value = (
            AgentBlueprintPublic(
                id="bp-1",
                name="v1",
                type="blueprint",
                values=[
                    AgentConfigValuePublic(key="temp", type="float", value="0.7"),
                ],
            )
        )

        # Should not raise even though we are technically inside a fake track context
        result = mock_opik_client.create_config(cfg)
        assert result == "v1"


# ---------------------------------------------------------------------------
# set_config_env tests
# ---------------------------------------------------------------------------


class TestSetConfigEnv:
    def test_set_config_env__resolves_and_tags__happyflow(
        self, mock_rest_client, mock_opik_client
    ):
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-123"
        )
        mock_rest_client.agent_configs.get_blueprint_by_name.return_value = mock.Mock(
            id="bp-v2"
        )

        mock_opik_client.set_config_env(
            project_name="my-project", version="v2", env="staging"
        )

        mock_rest_client.projects.retrieve_project.assert_called()
        mock_rest_client.agent_configs.get_blueprint_by_name.assert_called_once_with(
            project_id="proj-123",
            name="v2",
        )
        mock_rest_client.agent_configs.create_or_update_envs.assert_called_once()
        call_kwargs = (
            mock_rest_client.agent_configs.create_or_update_envs.call_args.kwargs
        )
        assert call_kwargs["project_id"] == "proj-123"
        envs = call_kwargs["envs"]
        assert len(envs) == 1
        assert envs[0].env_name == "staging"
        assert envs[0].blueprint_id == "bp-v2"

    def test_set_config_env__does_not_require_track_context(
        self, mock_rest_client, mock_opik_client
    ):
        """set_config_env should work without @opik.track context."""
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(
            id="proj-123"
        )
        mock_rest_client.agent_configs.get_blueprint_by_name.return_value = mock.Mock(
            id="bp-v1"
        )

        # Should not raise
        mock_opik_client.set_config_env(
            project_name="my-project", version="v1", env="prod"
        )

        mock_rest_client.agent_configs.create_or_update_envs.assert_called_once()


# ---------------------------------------------------------------------------
# Type inference tests
# ---------------------------------------------------------------------------


class TestTypeInference:
    """Types are derived from fallback instance values at runtime."""

    def test_none_value__inferred_as_str(self):
        from opik.api_objects.agent_config.base import _infer_python_type

        assert _infer_python_type(None) is str

    def test_float_value__inferred_as_float(self):
        from opik.api_objects.agent_config.base import _infer_python_type

        assert _infer_python_type(0.7) is float

    def test_str_value__inferred_as_str(self):
        from opik.api_objects.agent_config.base import _infer_python_type

        assert _infer_python_type("model") is str

    def test_int_value__inferred_as_int(self):
        from opik.api_objects.agent_config.base import _infer_python_type

        assert _infer_python_type(42) is int

    def test_bool_value__inferred_as_bool(self):
        from opik.api_objects.agent_config.base import _infer_python_type

        assert _infer_python_type(True) is bool

    def test_config_infer_field_types__returns_correct_mapping(self):
        class MyConfig(Config):
            temp: float
            model: str
            count: int
            enabled: bool

        cfg = MyConfig(temp=0.7, model="gpt-4", count=42, enabled=True)
        field_types = cfg._infer_field_types()

        assert field_types == {
            "temp": float,
            "model": str,
            "count": int,
            "enabled": bool,
        }

    def test_none_field__backend_type_is_string(self):
        """When a field value is None, the backend type sent should be 'string'."""
        from opik.api_objects import type_helpers
        from opik.api_objects.agent_config.base import _infer_python_type

        inferred = _infer_python_type(None)
        assert inferred is str
        assert type_helpers.python_type_to_backend_type(inferred) == "string"

    def test_float_field__backend_type_is_float(self):
        from opik.api_objects import type_helpers
        from opik.api_objects.agent_config.base import _infer_python_type

        assert (
            type_helpers.python_type_to_backend_type(_infer_python_type(0.7)) == "float"
        )

    def test_int_field__backend_type_is_integer(self):
        from opik.api_objects import type_helpers
        from opik.api_objects.agent_config.base import _infer_python_type

        assert (
            type_helpers.python_type_to_backend_type(_infer_python_type(42))
            == "integer"
        )

    def test_bool_field__backend_type_is_boolean(self):
        from opik.api_objects import type_helpers
        from opik.api_objects.agent_config.base import _infer_python_type

        assert (
            type_helpers.python_type_to_backend_type(_infer_python_type(True))
            == "boolean"
        )

    def test_type_hint_mismatched_with_value__value_type_wins(self):
        """Type annotations on fields are informational only. When the runtime
        value's type differs from the declared hint, the value wins."""

        class MyConfig(Config):
            declared_as_str: str
            declared_as_float: float
            declared_as_any: typing.Any

        cfg = MyConfig(
            declared_as_str=123,  # int value under an "str" hint
            declared_as_float=True,  # bool value under a "float" hint
            declared_as_any="model",  # str value under a "Any" hint
        )
        field_types = cfg._infer_field_types()

        assert field_types == {
            "declared_as_str": int,
            "declared_as_float": bool,
            "declared_as_any": str,
        }
