import dataclasses
import time
from typing import Callable
from unittest import mock

import pytest

from opik.api_objects.config.cache import SharedConfigCache
from opik.api_objects.config.decorator import config_decorator
from opik.api_objects.span.span_data import SpanData


class TestConfigDecoratorValidation:
    def test_non_dataclass__raises_type_error(self):
        with pytest.raises(TypeError, match="can only be applied to dataclasses"):

            @config_decorator
            class NotADataclass:
                pass

    def test_dataclass__preserves_dataclass_status(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        assert dataclasses.is_dataclass(MyConfig)

    def test_decorator__shared_cache_uses_default_ttl(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = object.__getattribute__(instance, "__opik_shared_cache__")
        assert cache._ttl_seconds == 300


class TestConfigDecoratorInit:
    def test_init__no_existing_blueprint__creates_config_with_all_fields(
        self, mock_backend
    ):
        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        MyConfig(temp=0.6, name="custom")

        mock_backend.optimizer_configs.create_config.assert_called_once()
        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert "MyConfig.temp" in keys
        assert "MyConfig.name" in keys

    def test_init__existing_blueprint_with_same_keys__no_create_called(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="number", value=0.3),
                mock.Mock(key="MyConfig.name", type="string", value="backend-agent"),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        MyConfig()

        mock_backend.optimizer_configs.create_config.assert_not_called()

    def test_init__existing_blueprint_applies_backend_values(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="number", value=0.3),
                mock.Mock(key="MyConfig.name", type="string", value="backend-agent"),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        instance = MyConfig()
        assert instance.temp == 0.3
        assert instance.name == "backend-agent"

    def test_init__existing_blueprint_with_extra_local_keys__creates_only_extra(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="number", value=0.3),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            max_tokens: int = 2000

        MyConfig()

        mock_backend.optimizer_configs.create_config.assert_called_once()
        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert "MyConfig.max_tokens" in keys
        assert "MyConfig.temp" not in keys

    def test_init__existing_blueprint_with_extra_local_keys__merges_values(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="number", value=0.3),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            max_tokens: int = 2000

        instance = MyConfig()

        assert instance.temp == 0.3
        assert instance.max_tokens == 2000

    def test_init__backend_unavailable__uses_local_defaults(self):
        with mock.patch(
            "opik.api_objects.opik_client.get_client_cached",
            side_effect=Exception("no backend"),
        ):

            @config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            assert instance.temp == 0.8


class TestConfigDecoratorFieldFiltering:
    def test_unsupported_field_types__excluded_from_backend_payload(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            callback: Callable = lambda: None  # type: ignore

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert "MyConfig.temp" in keys
        assert "MyConfig.callback" not in keys

    def test_inherited_dataclass__parent_fields_included(self, mock_backend):
        @dataclasses.dataclass
        class BaseConfig:
            base_field: str = "base"

        @config_decorator
        @dataclasses.dataclass
        class ChildConfig(BaseConfig):
            child_field: int = 42

        ChildConfig()

        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert "ChildConfig.base_field" in keys
        assert "ChildConfig.child_field" in keys


class TestConfigDecoratorMaskAndEnv:
    @pytest.mark.parametrize(
        "decorator_kwargs",
        [
            {"mask_id": "mask-1"},
            {"env": "prod"},
        ],
        ids=["mask_id", "env"],
    )
    def test_mask_or_env__no_existing_blueprint__creates_config(
        self, mock_backend, decorator_kwargs
    ):
        @config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        mock_backend.optimizer_configs.create_config.assert_called_once()

    @pytest.mark.parametrize(
        "decorator_kwargs",
        [
            {"mask_id": "mask-1"},
            {"env": "prod"},
        ],
        ids=["mask_id", "env"],
    )
    def test_mask_or_env__existing_blueprint__no_create_when_keys_match(
        self, mock_backend, decorator_kwargs
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="number", value=0.5)]
        )

        @config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        mock_backend.optimizer_configs.create_config.assert_not_called()

    @pytest.mark.parametrize(
        "decorator_kwargs",
        [
            {"mask_id": "mask-1"},
            {"env": "prod"},
        ],
        ids=["mask_id", "env"],
    )
    def test_mask_or_env__passed_to_get_blueprint(self, mock_backend, decorator_kwargs):
        @config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.get_blueprint.call_args[1]
        if "mask_id" in decorator_kwargs:
            assert call_kwargs.get("mask_id") == decorator_kwargs["mask_id"]
        if "env" in decorator_kwargs:
            assert call_kwargs.get("env") == decorator_kwargs["env"]


class TestConfigDecoratorSpanMetadata:
    def test_field_access_inside_span__injects_metadata(self, mock_backend):
        with mock.patch("opik.api_objects.config.decorator.context_storage") as mock_cs:
            mock_span_data = mock.Mock()
            mock_cs.top_span_data.return_value = mock_span_data

            @config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            _ = instance.temp

            mock_span_data.update.assert_called()
            call_kwargs = mock_span_data.update.call_args[1]
            assert "configuration" in call_kwargs["metadata"]
            config = call_kwargs["metadata"]["configuration"]
            assert "blueprint_id" in config
            assert "values" in config

    def test_multiple_field_accesses__configuration_present_after_each(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="number", value=0.8),
                mock.Mock(key="MyConfig.max_tokens", type="number", value=2000),
            ]
        )

        span_data = SpanData(
            trace_id="trace-1",
            metadata={"provider": "openai", "model": "gpt-4o"},
        )

        with mock.patch("opik.api_objects.config.decorator.context_storage") as mock_cs:
            mock_cs.top_span_data.return_value = span_data

            @config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8
                max_tokens: int = 2000

            instance = MyConfig()
            _ = instance.temp
            assert span_data.metadata["provider"] == "openai"
            assert "MyConfig.temp" in span_data.metadata["configuration"]["values"]
            assert (
                "MyConfig.max_tokens"
                not in span_data.metadata["configuration"]["values"]
            )

            _ = instance.max_tokens
            assert span_data.metadata["provider"] == "openai"
            assert "MyConfig.temp" in span_data.metadata["configuration"]["values"]
            assert (
                "MyConfig.max_tokens" in span_data.metadata["configuration"]["values"]
            )

    def test_field_access_outside_span__no_injection(self, mock_backend):
        with mock.patch("opik.api_objects.config.decorator.context_storage") as mock_cs:
            mock_cs.top_span_data.return_value = None

            @config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            _ = instance.temp


class TestConfigDecoratorMultiClass:
    def test_two_classes_same_project__share_single_cache(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        cache_m = object.__getattribute__(m, "__opik_shared_cache__")
        cache_p = object.__getattribute__(p, "__opik_shared_cache__")
        assert cache_m is cache_p

    def test_two_classes__keys_are_prefixed_with_class_name(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        ModelConfig()
        PromptConfig()

        all_calls = mock_backend.optimizer_configs.create_config.call_args_list
        all_keys = []
        for call in all_calls:
            call_kwargs = call[1]
            all_keys.extend(v["key"] for v in call_kwargs["blueprint"]["values"])

        assert "ModelConfig.temp" in all_keys
        assert "PromptConfig.template" in all_keys

    def test_first_class_creates_only_its_keys(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        ModelConfig()

        assert mock_backend.optimizer_configs.create_config.call_count == 1
        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert keys == ["ModelConfig.temp"]

    def test_second_class_adds_extra_keys(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        ModelConfig()

        mock_backend.set_blueprint_values(
            [mock.Mock(key="ModelConfig.temp", type="number", value=0.8)]
        )

        mock_backend.optimizer_configs.create_config.reset_mock()
        PromptConfig()

        mock_backend.optimizer_configs.create_config.assert_called_once()
        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert "PromptConfig.template" in keys
        assert "ModelConfig.temp" not in keys

    def test_backend_values_applied_to_correct_instances(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="number", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="backend"),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        assert m.temp == 0.5
        assert p.template == "backend"

    def test_single_fetch_populates_both_classes(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="number", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="backend"),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        cache = object.__getattribute__(m, "__opik_shared_cache__")
        assert "ModelConfig.temp" in cache.values
        assert "PromptConfig.template" in cache.values
        assert m.temp == 0.5
        assert p.template == "backend"

    def test_refetch_after_ttl__single_call_for_all(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="number", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="v1"),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        _p = PromptConfig()

        mock_backend.optimizer_configs.get_blueprint.reset_mock()

        cache: SharedConfigCache = object.__getattribute__(m, "__opik_shared_cache__")
        cache._last_fetch = time.monotonic() - cache._ttl_seconds - 1

        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="number", value=0.9),
                mock.Mock(key="PromptConfig.template", type="string", value="v2"),
            ]
        )

        _ = m.temp

        assert mock_backend.optimizer_configs.get_blueprint.call_count == 1

    def test_stale_refetch_via_classA__classB_sees_update(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="number", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="v1"),
            ]
        )

        @config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        cache: SharedConfigCache = object.__getattribute__(m, "__opik_shared_cache__")
        cache._last_fetch = time.monotonic() - cache._ttl_seconds - 1

        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="number", value=0.9),
                mock.Mock(key="PromptConfig.template", type="string", value="v2"),
            ]
        )

        _ = m.temp
        assert m.temp == 0.9

        assert cache.values["PromptConfig.template"] == "v2"
        _ = p.template
        assert p.template == "v2"

    def test_different_projects__separate_caches(self, mock_backend):
        @config_decorator(project="proj-a")
        @dataclasses.dataclass
        class ConfigA:
            val: int = 1

        @config_decorator(project="proj-b")
        @dataclasses.dataclass
        class ConfigB:
            val: int = 2

        a = ConfigA()
        b = ConfigB()

        cache_a = object.__getattribute__(a, "__opik_shared_cache__")
        cache_b = object.__getattribute__(b, "__opik_shared_cache__")
        assert cache_a is not cache_b

    def test_custom_name__uses_name_as_prefix(self, mock_backend):
        @config_decorator(name="custom")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
        assert "custom.temp" in keys
        assert "MyConfig.temp" not in keys


class TestConfigDecoratorTTLEnvVar:
    def test_default_ttl(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = object.__getattribute__(instance, "__opik_shared_cache__")
        assert cache._ttl_seconds == 300

    def test_env_var_overrides_ttl(self, mock_backend, monkeypatch):
        monkeypatch.setenv("OPIK_CONFIG_TTL_SECONDS", "60")

        from opik.api_objects.config.cache import clear_shared_caches

        clear_shared_caches()

        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = object.__getattribute__(instance, "__opik_shared_cache__")
        assert cache._ttl_seconds == 60
