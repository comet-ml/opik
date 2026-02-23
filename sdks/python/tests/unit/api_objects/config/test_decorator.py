import dataclasses
from typing import Callable
from unittest import mock

import pytest

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

    def test_decorator_with_ttl__sets_ttl_on_cache(self, mock_backend):
        @config_decorator(ttl_seconds=60)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = object.__getattribute__(instance, "__opik_cache__")
        assert cache._ttl_seconds == 60


class TestConfigDecoratorInit:
    def test_init__happy_path__syncs_with_backend(self, mock_backend):
        @config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        MyConfig(temp=0.6, name="custom")

        mock_backend.optimizer_configs.create_config.assert_called_once()

    def test_init__backend_returns_values__applies_them(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="temp", type="number", value=0.3),
                mock.Mock(key="name", type="string", value="backend-agent"),
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
        assert "temp" in keys
        assert "callback" not in keys

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
        assert "base_field" in keys
        assert "child_field" in keys


class TestConfigDecoratorMaskAndEnv:
    @pytest.mark.parametrize(
        "decorator_kwargs",
        [
            {"mask_id": "mask-1"},
            {"env": "prod"},
        ],
        ids=["mask_id", "env"],
    )
    def test_mask_or_env__triggers_second_blueprint_fetch(
        self, mock_backend, decorator_kwargs
    ):
        @config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        # First fetch is from create_config -> get_blueprint,
        # second is the pinned fetch for mask/env
        assert mock_backend.optimizer_configs.get_blueprint.call_count >= 2


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
            assert "config_id" in config
            assert "blueprint_id" in config
            assert "values" in config

    def test_multiple_field_accesses__configuration_present_after_each(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="temp", type="number", value=0.8),
                mock.Mock(key="max_tokens", type="number", value=2000),
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
            assert "temp" in span_data.metadata["configuration"]["values"]
            assert "max_tokens" not in span_data.metadata["configuration"]["values"]

            _ = instance.max_tokens
            assert span_data.metadata["provider"] == "openai"
            assert "temp" in span_data.metadata["configuration"]["values"]
            assert "max_tokens" in span_data.metadata["configuration"]["values"]

    def test_field_access_outside_span__no_injection(self, mock_backend):
        with mock.patch("opik.api_objects.config.decorator.context_storage") as mock_cs:
            mock_cs.top_span_data.return_value = None

            @config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            _ = instance.temp

            # No span_data.update should have been called since there's no span
