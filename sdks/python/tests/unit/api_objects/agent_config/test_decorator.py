import dataclasses
import time
from typing import Callable
from unittest import mock

import pytest

from opik.api_objects.agent_config.cache import SharedConfigCache
from opik.api_objects.agent_config.decorator import agent_config_decorator
from opik.api_objects.prompt.base_prompt import BasePrompt
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from opik.api_objects.trace.trace_data import TraceData
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail


class TestConfigDecoratorValidation:
    def test_non_dataclass__raises_type_error(self):
        with pytest.raises(TypeError, match="can only be applied to dataclasses"):

            @agent_config_decorator
            class NotADataclass:
                pass

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
        cache = object.__getattribute__(instance, "__opik_shared_cache__")
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

        @agent_config_decorator
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
                mock.Mock(key="MyConfig.temp", type="number", value=0.3),
            ]
        )

        @agent_config_decorator
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

        @agent_config_decorator
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

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            assert instance.temp == 0.8


class TestConfigDecoratorFieldFiltering:
    def test_unsupported_field_types__excluded_from_backend_payload(self, mock_backend):
        @agent_config_decorator
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

        @agent_config_decorator
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
        @agent_config_decorator(**decorator_kwargs)
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

        @agent_config_decorator(**decorator_kwargs)
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
        @agent_config_decorator(**decorator_kwargs)
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.get_blueprint.call_args[1]
        if "mask_id" in decorator_kwargs:
            assert call_kwargs.get("mask_id") == decorator_kwargs["mask_id"]
        if "env" in decorator_kwargs:
            assert call_kwargs.get("env") == decorator_kwargs["env"]


class TestConfigDecoratorTraceMetadata:
    def test_field_access_inside_trace__injects_metadata(self, mock_backend):
        with mock.patch(
            "opik.api_objects.agent_config.decorator.context_storage"
        ) as mock_cs:
            mock_trace_data = mock.Mock()
            mock_cs.get_trace_data.return_value = mock_trace_data

            @agent_config_decorator
            @dataclasses.dataclass
            class MyConfig:
                temp: float = 0.8

            instance = MyConfig()
            _ = instance.temp

            mock_trace_data.update.assert_called()
            call_kwargs = mock_trace_data.update.call_args[1]
            assert "agent_configuration" in call_kwargs["metadata"]
            config = call_kwargs["metadata"]["agent_configuration"]
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

        trace_data = TraceData(
            metadata={"provider": "openai", "model": "gpt-4o"},
        )

        with mock.patch(
            "opik.api_objects.agent_config.decorator.context_storage"
        ) as mock_cs:
            mock_cs.get_trace_data.return_value = trace_data

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

            _ = instance.max_tokens
            assert trace_data.metadata["provider"] == "openai"
            assert (
                "MyConfig.temp" in trace_data.metadata["agent_configuration"]["values"]
            )
            assert (
                "MyConfig.max_tokens"
                in trace_data.metadata["agent_configuration"]["values"]
            )

    def test_field_access_outside_trace__no_injection(self, mock_backend):
        with mock.patch(
            "opik.api_objects.agent_config.decorator.context_storage"
        ) as mock_cs:
            mock_cs.get_trace_data.return_value = None

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

        cache_m = object.__getattribute__(m, "__opik_shared_cache__")
        cache_p = object.__getattribute__(p, "__opik_shared_cache__")
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

        all_calls = mock_backend.optimizer_configs.create_config.call_args_list
        all_keys = []
        for call in all_calls:
            call_kwargs = call[1]
            all_keys.extend(v["key"] for v in call_kwargs["blueprint"]["values"])

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

        assert mock_backend.optimizer_configs.create_config.call_count == 1
        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        keys = [v["key"] for v in call_kwargs["blueprint"]["values"]]
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
                mock.Mock(key="ModelConfig.temp", type="number", value=0.5),
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

        cache_a = object.__getattribute__(a, "__opik_shared_cache__")
        cache_b = object.__getattribute__(b, "__opik_shared_cache__")
        assert cache_a is not cache_b

    def test_custom_name__uses_name_as_prefix(self, mock_backend):
        @agent_config_decorator(name="custom")
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
        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = object.__getattribute__(instance, "__opik_shared_cache__")
        assert cache._ttl_seconds == 300

    def test_env_var_overrides_ttl(self, mock_backend, monkeypatch):
        monkeypatch.setenv("OPIK_CONFIG_TTL_SECONDS", "60")

        from opik.api_objects.agent_config.cache import clear_shared_caches

        clear_shared_caches()

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            temp: float = 0.8

        instance = MyConfig()
        cache = object.__getattribute__(instance, "__opik_shared_cache__")
        assert cache._ttl_seconds == 60


class TestConfigDecoratorPromptFields:
    def test_prompt_field__sent_to_backend_as_version_id(self, mock_backend):
        fake_prompt = mock.Mock(spec=Prompt)
        fake_prompt.__internal_api__version_id__ = "ver-abc"

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            system_prompt: Prompt = dataclasses.field(
                default_factory=lambda: fake_prompt
            )

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        values = call_kwargs["blueprint"]["values"]
        prompt_param = next(v for v in values if v["key"] == "MyConfig.system_prompt")
        assert prompt_param["type"] == "prompt"
        assert prompt_param["value"] == "ver-abc"

    def test_chat_prompt_field__sent_to_backend_as_version_id(self, mock_backend):
        fake_prompt = mock.Mock(spec=ChatPrompt)
        fake_prompt.__internal_api__version_id__ = "ver-chat-1"

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            messages: ChatPrompt = dataclasses.field(
                default_factory=lambda: fake_prompt
            )

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        values = call_kwargs["blueprint"]["values"]
        param = next(v for v in values if v["key"] == "MyConfig.messages")
        assert param["value"] == "ver-chat-1"

    def test_existing_blueprint_prompt_field__resolves_and_applied_to_instance(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(
                    key="MyConfig.system_prompt", type="string", value="ver-backend"
                )
            ]
        )

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-id-1"
        version_detail.template_structure = "text"
        mock_backend.client.rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "my-prompt"
        mock_backend.client.rest_client.prompts.get_prompt_by_id.return_value = (
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
            [mock.Mock(key="MyConfig.messages", type="string", value="ver-chat")]
        )

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-id-2"
        version_detail.template_structure = "chat"
        mock_backend.client.rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "chat-prompt"
        mock_backend.client.rest_client.prompts.get_prompt_by_id.return_value = (
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
            [mock.Mock(key="MyConfig.p", type="prompt", value="ver-base")]
        )

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-base"
        version_detail.template_structure = "chat"
        mock_backend.client.rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "base-prompt"
        mock_backend.client.rest_client.prompts.get_prompt_by_id.return_value = (
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

    def test_prompt_version_field__sent_to_backend_as_version_id(self, mock_backend):
        fake_version = mock.Mock(spec=PromptVersionDetail)
        fake_version.id = "ver-pv-abc"

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            version: PromptVersionDetail = dataclasses.field(
                default_factory=lambda: fake_version
            )

        MyConfig()

        call_kwargs = mock_backend.optimizer_configs.create_config.call_args[1]
        values = call_kwargs["blueprint"]["values"]
        param = next(v for v in values if v["key"] == "MyConfig.version")
        assert param["type"] == "prompt_version"
        assert param["value"] == "ver-pv-abc"

    def test_existing_blueprint_prompt_version_field__resolves_to_prompt_version_detail(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(
                    key="MyConfig.version",
                    type="prompt_version",
                    value="ver-pv-backend",
                )
            ]
        )

        fake_version_detail = mock.Mock(spec=PromptVersionDetail)
        mock_backend.client.rest_client.prompts.get_prompt_version_by_id.return_value = fake_version_detail

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            version: PromptVersionDetail = dataclasses.field(default=None)

        instance = MyConfig()

        assert instance.version is fake_version_detail
        mock_backend.client.rest_client.prompts.get_prompt_version_by_id.assert_called_with(
            "ver-pv-backend"
        )
        mock_backend.client.rest_client.prompts.get_prompt_by_id.assert_not_called()

    def test_existing_blueprint_prompt_version_field__resolution_fails__field_omitted(
        self, mock_backend
    ):
        mock_backend.set_blueprint_values(
            [
                mock.Mock(
                    key="MyConfig.version",
                    type="prompt_version",
                    value="ver-pv-bad",
                )
            ]
        )
        mock_backend.client.rest_client.prompts.get_prompt_version_by_id.side_effect = (
            Exception("not found")
        )

        @agent_config_decorator
        @dataclasses.dataclass
        class MyConfig:
            version: PromptVersionDetail = dataclasses.field(default=None)

        instance = MyConfig()

        assert instance.version is None
