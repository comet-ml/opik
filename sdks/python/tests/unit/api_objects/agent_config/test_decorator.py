import dataclasses
import time
from typing import Annotated, Callable
from unittest import mock

import pytest

from opik.api_objects.agent_config.cache import SharedConfigCache
from opik.api_objects.agent_config.context import agent_config_context
from opik.api_objects.agent_config.decorator import (
    agent_config_decorator,
    get_cached_config,
)
from opik.api_objects.prompt.base_prompt import BasePrompt
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from opik.api_objects.trace.trace_data import TraceData
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail


class TestConfigDecoratorValidation:
    def test_plain_class__auto_converted_to_dataclass(self, mock_backend):
        @agent_config_decorator
        class MyConfig:
            temp: float = 0.8

        assert dataclasses.is_dataclass(MyConfig)

    def test_plain_class__instance_fields_accessible(self, mock_backend):
        @agent_config_decorator
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        instance = MyConfig()
        assert instance.temp == 0.8
        assert instance.name == "agent"

    def test_plain_class__no_defaults__requires_args_at_instantiation(
        self, mock_backend
    ):
        @agent_config_decorator
        class MyConfig:
            my_param: int
            name: str

        instance = MyConfig(my_param=11, name="Steve")
        assert instance.my_param == 11
        assert instance.name == "Steve"

    def test_dataclass__preserves_dataclass_status(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        assert dataclasses.is_dataclass(MyConfig)

    def test_decorator__shared_cache_uses_default_ttl(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = get_cached_config(instance)
        assert cache._ttl_seconds == 300


class TestConfigDecoratorInit:
    def test_init__no_existing_blueprint__creates_config_with_all_fields(
        self, mock_backend
    ):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        MyConfig(temp=0.6, name="custom")

        mock_backend.agent_configs.create_agent_config.assert_called_once()
        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        values_by_key = {v.key: v for v in blueprint.values}
        assert "MyConfig.temp" in values_by_key
        assert values_by_key["MyConfig.temp"].value == "0.6"
        assert values_by_key["MyConfig.temp"].type == "float"
        assert "MyConfig.name" in values_by_key
        assert values_by_key["MyConfig.name"].value == "custom"
        assert values_by_key["MyConfig.name"].type == "string"

    def test_init__existing_blueprint_with_same_keys__no_create_called(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="float", value=0.3),
                mock.Mock(key="MyConfig.name", type="string", value="backend-agent"),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            name: str = "agent"

        MyConfig()

        mock_backend.agent_configs.create_agent_config.assert_not_called()

    def test_init__existing_blueprint_applies_backend_values(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="float", value=0.3),
                mock.Mock(key="MyConfig.name", type="string", value="backend-agent"),
            ]
        )

        @agent_config_decorator
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
                mock.Mock(key="MyConfig.temp", type="float", value=0.3),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            max_tokens: int = 2000

        MyConfig()

        mock_backend.agent_configs.create_agent_config.assert_called_once()
        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        values_by_key = {v.key: v for v in blueprint.values}
        assert "MyConfig.max_tokens" in values_by_key
        assert values_by_key["MyConfig.max_tokens"].value == "2000"
        assert values_by_key["MyConfig.max_tokens"].type == "integer"
        assert "MyConfig.temp" not in values_by_key

    def test_init__existing_blueprint_with_extra_local_keys__merges_values(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="float", value=0.3),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            max_tokens: int = 2000

        instance = MyConfig()

        assert instance.temp == 0.3
        assert instance.max_tokens == 2000


class TestConfigDecoratorFieldFiltering:
    def test_unsupported_field_types__excluded_from_backend_payload(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8
            callback: Callable = lambda: None  # type: ignore

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        keys = [v.key for v in blueprint.values]
        assert "MyConfig.temp" in keys
        assert "MyConfig.callback" not in keys

    def test_inherited_dataclass__parent_fields_included(self, mock_backend):
        @dataclasses.dataclass
        class BaseConfig:
            base_field: str = "base"

        @agent_config_decorator
        @dataclasses.dataclass
        class ChildConfig(BaseConfig):
            child_field: int = 42

        ChildConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        keys = [v.key for v in blueprint.values]
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
        @agent_config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        mock_backend.agent_configs.create_agent_config.assert_called_once()

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
            [mock.Mock(key="MyConfig.temp", type="float", value=0.5)]
        )

        @agent_config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        mock_backend.agent_configs.create_agent_config.assert_not_called()

    @pytest.mark.parametrize(
        "decorator_kwargs,expected_method",
        [
            ({"mask_id": "mask-1"}, "get_latest_blueprint"),
            ({"env": "prod"}, "get_blueprint_by_env"),
        ],
        ids=["mask_id", "env"],
    )
    def test_mask_or_env__passed_to_get_blueprint(
        self, mock_backend, decorator_kwargs, expected_method
    ):
        @agent_config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        method = getattr(mock_backend.agent_configs, expected_method)
        call_kwargs = method.call_args[1]
        if "mask_id" in decorator_kwargs:
            assert call_kwargs.get("mask_id") == decorator_kwargs["mask_id"]
        if "env" in decorator_kwargs:
            assert call_kwargs.get("env_name") == decorator_kwargs["env"]

    def test_env__no_existing_blueprint__pins_new_blueprint_to_env(self, mock_backend):
        @agent_config_decorator(env="prod")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        mock_backend.agent_configs.create_or_update_envs.assert_called_once()
        call_kwargs = mock_backend.agent_configs.create_or_update_envs.call_args[1]
        assert call_kwargs["project_id"] == "proj-test"
        envs = call_kwargs["envs"]
        assert len(envs) == 1
        assert envs[0].env_name == "prod"
        assert envs[0].blueprint_id == "bp-test"

    def test_mask_id__no_existing_blueprint__does_not_pin_env(self, mock_backend):
        @agent_config_decorator(mask_id="mask-1")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        mock_backend.agent_configs.create_or_update_envs.assert_not_called()


class TestConfigDecoratorTraceMetadata:
    def test_field_access_inside_trace__injects_metadata(self, mock_backend):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        with mock.patch(
            "opik.api_objects.agent_config.decorator.opik_context.update_current_trace"
        ) as mock_update:

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            _ = instance.temp

            mock_update.assert_called()
            call_kwargs = mock_update.call_args[1]
            assert "agent_configuration" in call_kwargs["metadata"]
            config = call_kwargs["metadata"]["agent_configuration"]
            assert "blueprint_id" in config
            assert "values" in config
            field_entry = config["values"]["MyConfig.temp"]
            assert field_entry["value"] == 0.8
            assert field_entry["type"] == "float"
            assert "description" not in field_entry

    def test_field_access_inside_trace__annotated_field__injects_description(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        with mock.patch(
            "opik.api_objects.agent_config.decorator.opik_context.update_current_trace"
        ) as mock_update:

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: Annotated[float, "sampling temperature"] = 0.8

            instance = MyConfig()
            _ = instance.temp

            mock_update.assert_called()
            field_entry = mock_update.call_args[1]["metadata"]["agent_configuration"][
                "values"
            ]["MyConfig.temp"]
            assert field_entry["value"] == 0.8
            assert field_entry["type"] == "float"
            assert field_entry["description"] == "sampling temperature"

    def test_multiple_field_accesses__configuration_present_after_each(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="MyConfig.temp", type="float", value=0.8),
                mock.Mock(key="MyConfig.max_tokens", type="integer", value=2000),
            ]
        )

        trace_data = TraceData(
            metadata={"provider": "openai", "model": "gpt-4o"},
        )

        with mock.patch(
            "opik.opik_context.context_storage.get_trace_data",
            return_value=trace_data,
        ):

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8
                max_tokens: int = 2000

            instance = MyConfig()
            _ = instance.temp
            assert trace_data.metadata["provider"] == "openai"
            assert (
                "MyConfig.temp" in trace_data.metadata["agent_configuration"]["values"]
            )
            assert (
                "MyConfig.max_tokens"
                not in trace_data.metadata["agent_configuration"]["values"]
            )
            temp_entry = trace_data.metadata["agent_configuration"]["values"][
                "MyConfig.temp"
            ]
            assert temp_entry["value"] == 0.8
            assert temp_entry["type"] == "float"

            _ = instance.max_tokens
            assert trace_data.metadata["provider"] == "openai"
            assert (
                "MyConfig.temp" in trace_data.metadata["agent_configuration"]["values"]
            )
            assert (
                "MyConfig.max_tokens"
                in trace_data.metadata["agent_configuration"]["values"]
            )
            max_tokens_entry = trace_data.metadata["agent_configuration"]["values"][
                "MyConfig.max_tokens"
            ]
            assert max_tokens_entry["value"] == 2000
            assert max_tokens_entry["type"] == "integer"

    @pytest.mark.parametrize(
        "py_type,backend_value,expected_injected_value,expected_type",
        [
            (str, "hello", "hello", "string"),
            (int, 42, 42, "integer"),
            (float, 3.14, 3.14, "float"),
            (bool, True, True, "boolean"),
        ],
        ids=["str", "int", "float", "bool"],
    )
    def test_field_access_inside_trace__injects_correct_value_and_type(
        self,
        mock_backend,
        py_type,
        backend_value,
        expected_injected_value,
        expected_type,
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.field", type=expected_type, value=backend_value)]
        )

        with mock.patch(
            "opik.api_objects.agent_config.decorator.opik_context.update_current_trace"
        ) as mock_update:

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                field: py_type = dataclasses.field(default=None)

            instance = MyConfig()
            _ = instance.field

            mock_update.assert_called()
            field_entry = mock_update.call_args[1]["metadata"]["agent_configuration"][
                "values"
            ]["MyConfig.field"]
            assert field_entry["value"] == expected_injected_value
            assert field_entry["type"] == expected_type

    def test_prompt_field_access_inside_trace__injects_commit_string(
        self, mock_backend
    ):
        fake_prompt = mock.Mock(spec=Prompt)
        fake_prompt.commit = "abc12345"

        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.system_prompt", type="prompt", value="abc12345")]
        )

        version_detail = mock.Mock()
        version_detail.template_structure = "text"
        prompt_detail = mock.Mock()
        prompt_detail.name = "my-prompt"
        prompt_detail.requested_version = version_detail
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.return_value = (
            prompt_detail
        )

        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=fake_prompt,
        ):
            with mock.patch(
                "opik.api_objects.agent_config.decorator.opik_context.update_current_trace"
            ) as mock_update:

                @agent_config_decorator
                @dataclasses.dataclass
                class MyConfig:
                    system_prompt: Prompt = dataclasses.field(default=None)

                instance = MyConfig()
                _ = instance.system_prompt

                mock_update.assert_called()
                field_entry = mock_update.call_args[1]["metadata"][
                    "agent_configuration"
                ]["values"]["MyConfig.system_prompt"]
                assert field_entry["value"] == "abc12345"
                assert field_entry["type"] == "prompt"

    def test_prompt_version_field_access_inside_trace__injects_commit_string(
        self, mock_backend
    ):
        fake_version = mock.Mock(spec=PromptVersionDetail)
        fake_version.commit = "pv123456"

        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.version", type="prompt_commit", value="pv123456")]
        )

        prompt_detail = mock.Mock()
        prompt_detail.requested_version = fake_version
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.return_value = (
            prompt_detail
        )

        with mock.patch(
            "opik.api_objects.agent_config.decorator.opik_context.update_current_trace"
        ) as mock_update:

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                version: PromptVersionDetail = dataclasses.field(default=None)

            instance = MyConfig()
            _ = instance.version

            mock_update.assert_called()
            field_entry = mock_update.call_args[1]["metadata"]["agent_configuration"][
                "values"
            ]["MyConfig.version"]
            assert field_entry["value"] == "pv123456"
            assert field_entry["type"] == "prompt_commit"

    def test_field_access_outside_trace__no_injection(self, mock_backend):
        from opik import exceptions as opik_exceptions

        with mock.patch(
            "opik.api_objects.agent_config.decorator.opik_context.update_current_trace",
            side_effect=opik_exceptions.OpikException("no trace"),
        ):

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            _ = instance.temp


class TestConfigDecoratorMultiClass:
    def test_two_classes_same_project__share_single_cache(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        cache_m = get_cached_config(m)
        cache_p = get_cached_config(p)
        assert cache_m is cache_p

    def test_two_classes__keys_are_prefixed_with_class_name(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        ModelConfig()
        PromptConfig()

        all_calls = mock_backend.agent_configs.create_agent_config.call_args_list
        all_keys = []
        for call in all_calls:
            call_kwargs = call[1]
            all_keys.extend(v.key for v in call_kwargs["blueprint"].values)

        assert "ModelConfig.temp" in all_keys
        assert "PromptConfig.template" in all_keys

    def test_first_class_creates_only_its_keys(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        ModelConfig()

        assert mock_backend.agent_configs.create_agent_config.call_count == 1
        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        keys = [v.key for v in blueprint.values]
        assert keys == ["ModelConfig.temp"]

    def test_second_class_adds_extra_keys(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        ModelConfig()

        mock_backend.set_blueprint_values(
            [mock.Mock(key="ModelConfig.temp", type="float", value=0.8)]
        )

        mock_backend.agent_configs.create_agent_config.reset_mock()
        PromptConfig()

        mock_backend.agent_configs.create_agent_config.assert_called_once()
        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        keys = [v.key for v in blueprint.values]
        assert "PromptConfig.template" in keys
        assert "ModelConfig.temp" not in keys

    def test_backend_values_applied_to_correct_instances(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="float", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="backend"),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
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
                mock.Mock(key="ModelConfig.temp", type="float", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="backend"),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        cache = get_cached_config(m)
        assert "ModelConfig.temp" in cache.values
        assert "PromptConfig.template" in cache.values
        assert m.temp == 0.5
        assert p.template == "backend"

    def test_background_refresh_after_ttl__updates_cache(self, mock_backend):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="float", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="v1"),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        _p = PromptConfig()

        cache: SharedConfigCache = get_cached_config(m)

        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="float", value=0.9),
                mock.Mock(key="PromptConfig.template", type="string", value="v2"),
            ]
        )

        cache._last_fetch = time.monotonic() - cache._ttl_seconds - 1
        cache.try_background_refresh()

        assert cache.values["ModelConfig.temp"] == 0.9
        assert cache.values["PromptConfig.template"] == "v2"

    def test_background_refresh__classB_sees_update_from_classA_refresh(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="float", value=0.5),
                mock.Mock(key="PromptConfig.template", type="string", value="v1"),
            ]
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class ModelConfig:
            temp: float = 0.8

        @agent_config_decorator
        @dataclasses.dataclass
        class PromptConfig:
            template: str = "hello"

        m = ModelConfig()
        p = PromptConfig()

        cache: SharedConfigCache = get_cached_config(m)

        mock_backend.set_blueprint_values(
            [
                mock.Mock(key="ModelConfig.temp", type="float", value=0.9),
                mock.Mock(key="PromptConfig.template", type="string", value="v2"),
            ]
        )

        cache._last_fetch = time.monotonic() - cache._ttl_seconds - 1
        cache.try_background_refresh()

        assert m.temp == 0.9
        assert p.template == "v2"

    def test_different_projects__separate_caches(self, mock_backend):
        @agent_config_decorator(project="proj-a")
        @dataclasses.dataclass
        class ConfigA:
            val: int = 1

        @agent_config_decorator(project="proj-b")
        @dataclasses.dataclass
        class ConfigB:
            val: int = 2

        a = ConfigA()
        b = ConfigB()

        cache_a = get_cached_config(a)
        cache_b = get_cached_config(b)
        assert cache_a is not cache_b

    def test_custom_name__uses_name_as_prefix(self, mock_backend):
        @agent_config_decorator(name="custom")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        keys = [v.key for v in blueprint.values]
        assert "custom.temp" in keys
        assert "MyConfig.temp" not in keys


class TestConfigDecoratorTTLEnvVar:
    def test_default_ttl(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = get_cached_config(instance)
        assert cache._ttl_seconds == 300

    def test_env_var_overrides_ttl(self, mock_backend, monkeypatch):
        monkeypatch.setenv("OPIK_CONFIG_TTL_SECONDS", "60")

        from opik.api_objects.agent_config.cache import _registry

        _registry.clear()

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = get_cached_config(instance)
        assert cache._ttl_seconds == 60


class TestConfigDecoratorPromptFields:
    def test_prompt_field__sent_to_backend_as_commit(self, mock_backend):
        fake_prompt = mock.Mock(spec=Prompt)
        fake_prompt.commit = "abc12345"

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            system_prompt: Prompt = dataclasses.field(
                default_factory=lambda: fake_prompt
            )

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        prompt_param = next(
            v for v in blueprint.values if v.key == "MyConfig.system_prompt"
        )
        assert prompt_param.type == "prompt"
        assert prompt_param.value == "abc12345"

    def test_chat_prompt_field__sent_to_backend_as_commit(self, mock_backend):
        fake_prompt = mock.Mock(spec=ChatPrompt)
        fake_prompt.commit = "bcd23456"

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            messages: ChatPrompt = dataclasses.field(
                default_factory=lambda: fake_prompt
            )

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        param = next(v for v in blueprint.values if v.key == "MyConfig.messages")
        assert param.value == "bcd23456"

    def test_existing_blueprint_prompt_field__resolves_and_applied_to_instance(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.system_prompt", type="string", value="abc12345")]
        )

        version_detail = mock.Mock()
        version_detail.template_structure = "text"
        prompt_detail = mock.Mock()
        prompt_detail.name = "my-prompt"
        prompt_detail.requested_version = version_detail
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.return_value = (
            prompt_detail
        )

        fake_prompt = mock.Mock(spec=Prompt)

        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=fake_prompt,
        ):

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                system_prompt: Prompt = dataclasses.field(default=None)

            instance = MyConfig()

        assert instance.system_prompt is fake_prompt

    def test_existing_blueprint_chat_prompt_field__resolves_chat_prompt(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.messages", type="string", value="bcd23456")]
        )

        version_detail = mock.Mock()
        version_detail.template_structure = "chat"
        prompt_detail = mock.Mock()
        prompt_detail.name = "chat-prompt"
        prompt_detail.requested_version = version_detail
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.return_value = (
            prompt_detail
        )

        fake_chat_prompt = mock.Mock(spec=ChatPrompt)

        with mock.patch(
            "opik.api_objects.prompt.chat.chat_prompt.ChatPrompt.from_fern_prompt_version",
            return_value=fake_chat_prompt,
        ):

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                messages: ChatPrompt = dataclasses.field(default=None)

            instance = MyConfig()

        assert instance.messages is fake_chat_prompt

    def test_base_prompt_annotation__dispatches_on_template_structure(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.p", type="prompt", value="cde34567")]
        )

        version_detail = mock.Mock()
        version_detail.template_structure = "chat"
        prompt_detail = mock.Mock()
        prompt_detail.name = "base-prompt"
        prompt_detail.requested_version = version_detail
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.return_value = (
            prompt_detail
        )

        fake_chat_prompt = mock.Mock(spec=ChatPrompt)

        with mock.patch(
            "opik.api_objects.prompt.chat.chat_prompt.ChatPrompt.from_fern_prompt_version",
            return_value=fake_chat_prompt,
        ):

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                p: BasePrompt = dataclasses.field(default=None)

            instance = MyConfig()

        assert instance.p is fake_chat_prompt

    def test_prompt_version_field__sent_to_backend_as_commit(self, mock_backend):
        fake_version = mock.Mock(spec=PromptVersionDetail)
        fake_version.commit = "pv123456"

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            version: PromptVersionDetail = dataclasses.field(
                default_factory=lambda: fake_version
            )

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        param = next(v for v in blueprint.values if v.key == "MyConfig.version")
        assert param.type == "prompt_commit"
        assert param.value == "pv123456"

    def test_existing_blueprint_prompt_version_field__resolves_to_prompt_version_detail(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(
                    key="MyConfig.version",
                    type="prompt_commit",
                    value="pv111111",
                )
            ]
        )

        fake_version_detail = mock.Mock(spec=PromptVersionDetail)
        prompt_detail = mock.Mock()
        prompt_detail.requested_version = fake_version_detail
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.return_value = (
            prompt_detail
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            version: PromptVersionDetail = dataclasses.field(default=None)

        instance = MyConfig()

        assert instance.version is fake_version_detail
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.assert_called_with(
            "pv111111"
        )

    def test_existing_blueprint_prompt_version_field__resolution_fails__raises(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(
                    key="MyConfig.version",
                    type="prompt_commit",
                    value="badbad00",
                )
            ]
        )
        mock_backend.client.rest_client.prompts.get_prompt_by_commit.side_effect = (
            Exception("not found")
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            version: PromptVersionDetail = dataclasses.field(default=None)

        with pytest.raises(Exception, match="not found"):
            MyConfig()


class TestConfigDecoratorContextMask:
    def test_context_mask__attribute_returns_masked_value(self, mock_backend):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        mock_backend.set_mask_blueprint_values(
            "mask-ctx",
            [mock.Mock(key="MyConfig.temp", type="float", value=0.2)],
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        assert instance.temp == 0.8

        with agent_config_context("mask-ctx"):
            assert instance.temp == 0.2

    def test_context_mask__takes_precedence_over_class_mask(self, mock_backend):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.5)]
        )
        mock_backend.set_mask_blueprint_values(
            "mask-ctx",
            [mock.Mock(key="MyConfig.temp", type="float", value=0.1)],
        )

        @agent_config_decorator(mask_id="class-mask")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        assert instance.temp == 0.5

        with agent_config_context("mask-ctx"):
            assert instance.temp == 0.1

    def test_context_mask__calls_get_blueprint_with_mask_id(self, mock_backend):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        mock_backend.set_mask_blueprint_values(
            "mask-ctx",
            [mock.Mock(key="MyConfig.temp", type="float", value=0.2)],
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        mock_backend.agent_configs.get_latest_blueprint.reset_mock()

        with agent_config_context("mask-ctx"):
            _ = instance.temp

        call_kwargs = mock_backend.agent_configs.get_latest_blueprint.call_args[1]
        assert call_kwargs.get("mask_id") == "mask-ctx"

    def test_context_mask__env_pinned_instance__calls_get_blueprint_by_env_with_mask_id(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        mock_backend.set_mask_blueprint_values(
            "mask-ctx",
            [mock.Mock(key="MyConfig.temp", type="float", value=0.3)],
        )

        @agent_config_decorator(env="prod")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        mock_backend.agent_configs.get_blueprint_by_env.reset_mock()

        with agent_config_context("mask-ctx"):
            _ = instance.temp

        call_kwargs = mock_backend.agent_configs.get_blueprint_by_env.call_args[1]
        assert call_kwargs.get("mask_id") == "mask-ctx"
        assert call_kwargs.get("env_name") == "prod"

    def test_context_mask__env_pinned_instance__returns_masked_value(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        mock_backend.set_mask_blueprint_values(
            "mask-ctx",
            [mock.Mock(key="MyConfig.temp", type="float", value=0.3)],
        )

        @agent_config_decorator(env="prod")
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        assert instance.temp == 0.8

        with agent_config_context("mask-ctx"):
            assert instance.temp == 0.3

    def test_context_mask__no_env__does_not_call_get_blueprint_by_env(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [mock.Mock(key="MyConfig.temp", type="float", value=0.8)]
        )
        mock_backend.set_mask_blueprint_values(
            "mask-ctx",
            [mock.Mock(key="MyConfig.temp", type="float", value=0.5)],
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        mock_backend.agent_configs.get_blueprint_by_env.reset_mock()

        with agent_config_context("mask-ctx"):
            _ = instance.temp

        mock_backend.agent_configs.get_blueprint_by_env.assert_not_called()


class TestConfigDecoratorDefaultFactory:
    def test_default_factory_prompt__instantiated_exactly_once(self, mock_backend):
        factory_call_count = 0

        def counting_factory():
            nonlocal factory_call_count
            factory_call_count += 1
            p = mock.Mock(spec=Prompt)
            p.commit = "abc123"
            return p

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            system_prompt: Prompt = dataclasses.field(default_factory=counting_factory)

        assert factory_call_count == 0, "factory must not be called at decoration time"
        config = MyConfig()
        assert factory_call_count == 1, (
            "factory must be called exactly once on instantiation"
        )
        config.system_prompt
        assert factory_call_count == 1, "factory must not be called on attribute access"


class TestConfigDecoratorAnnotatedDescriptions:
    def test_annotated_field__description_sent_to_backend(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            model: Annotated[str, "The LLM model to use"] = "gpt-4o"

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        values_by_key = {v.key: v for v in blueprint.values}
        assert values_by_key["MyConfig.model"].description == "The LLM model to use"

    def test_mixed_fields__descriptions_per_field(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            model: Annotated[str, "LLM model"] = "gpt-4o"
            temperature: float = 0.7

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        values_by_key = {v.key: v for v in blueprint.values}
        assert values_by_key["MyConfig.model"].description == "LLM model"
        assert values_by_key["MyConfig.temperature"].description is None

    def test_annotated_non_str_metadata__no_description(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            tokens: Annotated[int, 42] = 512

        MyConfig()

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        values_by_key = {v.key: v for v in blueprint.values}
        assert values_by_key["MyConfig.tokens"].description is None

    def test_annotated_fields__base_type_and_value_unchanged(self, mock_backend):
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temperature: Annotated[float, "Sampling temp"] = 0.9

        MyConfig(temperature=0.5)

        call_kwargs = mock_backend.agent_configs.create_agent_config.call_args[1]
        blueprint = call_kwargs["blueprint"]
        values_by_key = {v.key: v for v in blueprint.values}
        assert values_by_key["MyConfig.temperature"].value == "0.5"
        assert values_by_key["MyConfig.temperature"].type == "float"
        assert values_by_key["MyConfig.temperature"].description == "Sampling temp"
