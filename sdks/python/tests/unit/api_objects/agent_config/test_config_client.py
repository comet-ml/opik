from unittest import mock

import pytest

from opik.api_objects.agent_config.client import ConfigClient, ConfigData
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from opik.api_objects.prompt.base_prompt import BasePrompt
from opik.rest_api.types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
    OptimizerConfigCreateResponse,
    OptimizerConfigParameter,
)


def _make_create_response():
    return OptimizerConfigCreateResponse(id="cfg-123")


def _make_blueprint(
    blueprint_id="bp-456",
    values=None,
    description=None,
):
    if values is None:
        values = [
            OptimizerConfigParameter(key="temperature", type="number", value=0.6),
            OptimizerConfigParameter(key="name", type="string", value="agent"),
        ]
    return OptimizerConfigBlueprint(
        id=blueprint_id,
        values=values,
        description=description,
    )


@pytest.fixture
def mock_rest_client():
    client = mock.Mock()
    client.optimizer_configs = mock.Mock()
    return client


@pytest.fixture
def config_client(mock_rest_client):
    return ConfigClient(mock_rest_client)


class TestCreateConfig:
    def test_create__happy_path__calls_backend_and_returns_config_data(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.create_config(
            fields_with_values={
                "temperature": (float, 0.6),
                "name": (str, "agent"),
            },
            project_name="my-project",
        )

        mock_rest_client.optimizer_configs.create_config.assert_called_once()
        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["blueprint"]["type"] == "blueprint"
        assert call_kwargs["blueprint"]["values"] is not None

        mock_rest_client.optimizer_configs.get_blueprint.assert_called_once()

        assert isinstance(result, ConfigData)
        assert result.blueprint_id == "bp-456"

    def test_create__bool_field__serialized_as_string_type(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_config(
            fields_with_values={"flag": (bool, False)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        values = call_kwargs["blueprint"]["values"]
        flag_param = [v for v in values if v["key"] == "flag"][0]
        assert flag_param["type"] == "string"
        assert flag_param["value"] == "false"

    def test_create__with_project_name__passes_project_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["project_name"] == "my-project"

    def test_create__with_project_name__get_blueprint_uses_resolved_project_id(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
        )

        get_bp_kwargs = mock_rest_client.optimizer_configs.get_blueprint.call_args[1]
        assert get_bp_kwargs.get("project_id") == "proj-1"

    def test_create__with_project_id__passes_project_id_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_config(
            fields_with_values={"temp": (float, 0.5)},
            project_name="my-project",
            project_id="proj-99",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["project_id"] == "proj-99"


class TestGetBlueprint:
    def test_get_blueprint__happy_path__returns_config_data(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            blueprint_id="bp-789"
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.get_blueprint(project_name="my-project")

        assert result.blueprint_id == "bp-789"
        assert "temperature" in result.values
        assert "name" in result.values

    def test_get_blueprint__with_field_types__deserializes_values(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(key="temperature", type="number", value="0.6"),
                OptimizerConfigParameter(key="flag", type="string", value="true"),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.get_blueprint(
            project_name="my-project",
            field_types={"temperature": float, "flag": bool},
        )

        assert result.values["temperature"] == 0.6
        assert result.values["flag"] is True

    def test_get_blueprint__prompt_field__resolves_to_prompt_object(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(
                    key="system_prompt", type="string", value="ver-111"
                ),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-1"
        version_detail.template_structure = "text"
        mock_rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "my-prompt"
        mock_rest_client.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_prompt = mock.Mock(spec=Prompt)
        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=fake_prompt,
        ):
            result = config_client.get_blueprint(
                project_name="my-project",
                field_types={"system_prompt": Prompt},
            )

        assert result.values["system_prompt"] is fake_prompt
        mock_rest_client.prompts.get_prompt_version_by_id.assert_called_once_with(
            "ver-111"
        )
        mock_rest_client.prompts.get_prompt_by_id.assert_called_once_with("prompt-1")

    def test_get_blueprint__chat_prompt_field__resolves_to_chat_prompt_object(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(
                    key="messages", type="string", value="ver-222"
                ),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-2"
        version_detail.template_structure = "chat"
        mock_rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "chat-prompt"
        mock_rest_client.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_chat_prompt = mock.Mock(spec=ChatPrompt)
        with mock.patch(
            "opik.api_objects.prompt.chat.chat_prompt.ChatPrompt.from_fern_prompt_version",
            return_value=fake_chat_prompt,
        ):
            result = config_client.get_blueprint(
                project_name="my-project",
                field_types={"messages": ChatPrompt},
            )

        assert result.values["messages"] is fake_chat_prompt

    def test_get_blueprint__base_prompt_annotation__chat_structure_resolves_to_chat_prompt(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(key="p", type="string", value="ver-333"),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-3"
        version_detail.template_structure = "chat"
        mock_rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "any-prompt"
        mock_rest_client.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_chat_prompt = mock.Mock(spec=ChatPrompt)
        with mock.patch(
            "opik.api_objects.prompt.chat.chat_prompt.ChatPrompt.from_fern_prompt_version",
            return_value=fake_chat_prompt,
        ):
            result = config_client.get_blueprint(
                project_name="my-project",
                field_types={"p": BasePrompt},
            )

        assert result.values["p"] is fake_chat_prompt

    def test_get_blueprint__base_prompt_annotation__text_structure_resolves_to_prompt(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(key="p", type="string", value="ver-444"),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-4"
        version_detail.template_structure = "text"
        mock_rest_client.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "any-prompt"
        mock_rest_client.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_prompt = mock.Mock(spec=Prompt)
        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=fake_prompt,
        ):
            result = config_client.get_blueprint(
                project_name="my-project",
                field_types={"p": BasePrompt},
            )

        assert result.values["p"] is fake_prompt

    def test_get_blueprint__prompt_resolution_fails__omits_key_from_values(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(
                    key="system_prompt", type="string", value="ver-bad"
                ),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")
        mock_rest_client.prompts.get_prompt_version_by_id.side_effect = Exception(
            "network error"
        )

        result = config_client.get_blueprint(
            project_name="my-project",
            field_types={"system_prompt": Prompt},
        )

        assert "system_prompt" not in result.values

    def test_get_blueprint__prompt_field__makes_exactly_two_api_calls_per_prompt(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            values=[
                OptimizerConfigParameter(key="p1", type="string", value="ver-1"),
                OptimizerConfigParameter(key="p2", type="string", value="ver-2"),
            ]
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        def _version_side_effect(vid):
            v = mock.Mock()
            v.prompt_id = f"prompt-{vid}"
            v.template_structure = "text"
            return v

        mock_rest_client.prompts.get_prompt_version_by_id.side_effect = (
            _version_side_effect
        )

        def _detail_side_effect(pid):
            d = mock.Mock()
            d.name = f"name-{pid}"
            return d

        mock_rest_client.prompts.get_prompt_by_id.side_effect = _detail_side_effect

        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=mock.Mock(spec=Prompt),
        ):
            config_client.get_blueprint(
                project_name="my-project",
                field_types={"p1": Prompt, "p2": Prompt},
            )

        assert mock_rest_client.prompts.get_prompt_version_by_id.call_count == 2
        assert mock_rest_client.prompts.get_prompt_by_id.call_count == 2

    @pytest.mark.parametrize(
        "mask_id",
        ["mask-1", "mask-2", None],
        ids=["mask_1", "mask_2", "no_mask"],
    )
    def test_get_blueprint__mask_id__passed_to_backend(
        self, config_client, mock_rest_client, mask_id
    ):
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.get_blueprint(
            project_name="my-project",
            mask_id=mask_id,
        )

        mock_rest_client.optimizer_configs.get_blueprint.assert_called_once_with(
            project_id="proj-1",
            env=None,
            mask_id=mask_id,
        )


class TestCreateMask:
    def test_create_mask__happy_path__calls_backend_with_mask_type(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["blueprint"]["type"] == "mask"
        assert call_kwargs["blueprint"]["values"] is not None

    def test_create_mask__sends_under_blueprint_key(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert "blueprint" in call_kwargs
        assert "mask" not in call_kwargs

    def test_create_mask__returns_config_data(self, config_client, mock_rest_client):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = _make_blueprint(
            blueprint_id="bp-mask"
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        result = config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
        )

        assert isinstance(result, ConfigData)
        assert result.blueprint_id == "bp-mask"

    def test_create_mask__with_description__passes_description_in_mask_payload(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
            description="variant-A",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["blueprint"]["description"] == "variant-A"

    def test_create_mask__with_project_id__passes_project_id_to_backend(
        self, config_client, mock_rest_client
    ):
        mock_rest_client.optimizer_configs.create_config.return_value = (
            _make_create_response()
        )
        mock_rest_client.optimizer_configs.get_blueprint.return_value = (
            _make_blueprint()
        )
        mock_rest_client.projects.retrieve_project.return_value = mock.Mock(id="proj-1")

        config_client.create_mask(
            fields_with_values={"temperature": (float, 0.3)},
            project_name="my-project",
            project_id="proj-99",
        )

        call_kwargs = mock_rest_client.optimizer_configs.create_config.call_args[1]
        assert call_kwargs["project_id"] == "proj-99"
