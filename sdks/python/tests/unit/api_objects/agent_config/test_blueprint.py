from typing import List
from unittest import mock

import pytest

from opik.api_objects.agent_config.blueprint import Blueprint
from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
from opik.api_objects.prompt.base_prompt import BasePrompt
from opik.rest_api.types.agent_blueprint_public import AgentBlueprintPublic
from opik.rest_api.types.agent_config_value_public import AgentConfigValuePublic
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail


def _make_raw_blueprint(
    blueprint_id="bp-1",
    values=None,
    description=None,
    bp_type="blueprint",
    envs=None,
    created_by=None,
    created_at=None,
):
    if values is None:
        values = [
            AgentConfigValuePublic(key="temperature", type="number", value="0.6"),
            AgentConfigValuePublic(key="name", type="string", value="agent"),
        ]
    return AgentBlueprintPublic(
        id=blueprint_id,
        type=bp_type,
        values=values,
        description=description,
        envs=envs,
        created_by=created_by,
        created_at=created_at,
    )


class TestBlueprintProperties:
    def test_id(self):
        bp = Blueprint(_make_raw_blueprint(blueprint_id="bp-42"))
        assert bp.id == "bp-42"

    def test_description(self):
        bp = Blueprint(_make_raw_blueprint(description="test desc"))
        assert bp.description == "test desc"

    def test_type(self):
        bp = Blueprint(_make_raw_blueprint(bp_type="mask"))
        assert bp.type == "mask"

    def test_envs(self):
        bp = Blueprint(_make_raw_blueprint(envs=["prod", "staging"]))
        assert bp.envs == ["prod", "staging"]

    def test_created_by(self):
        bp = Blueprint(_make_raw_blueprint(created_by="user-1"))
        assert bp.created_by == "user-1"


class TestBlueprintValueResolution:
    def test_without_field_types__values_are_raw_strings(self):
        bp = Blueprint(_make_raw_blueprint())
        assert bp["temperature"] == "0.6"
        assert bp["name"] == "agent"

    def test_with_field_types__deserializes_values(self):
        bp = Blueprint(
            _make_raw_blueprint(),
            field_types={"temperature": float, "name": str},
        )
        assert bp["temperature"] == 0.6
        assert bp["name"] == "agent"

    def test_with_field_types__bool_deserialization(self):
        raw = _make_raw_blueprint(
            values=[AgentConfigValuePublic(key="flag", type="string", value="true")]
        )
        bp = Blueprint(raw, field_types={"flag": bool})
        assert bp["flag"] is True

    def test_with_field_types__int_deserialization(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(key="count", type="number", value="42"),
            ]
        )
        bp = Blueprint(raw, field_types={"count": int})
        assert bp["count"] == 42


class TestBlueprintDictLikeAccess:
    def test_get__existing_key(self):
        bp = Blueprint(_make_raw_blueprint())
        assert bp.get("temperature") == "0.6"

    def test_get__missing_key__returns_default(self):
        bp = Blueprint(_make_raw_blueprint())
        assert bp.get("missing", 42) == 42

    def test_get__missing_key_no_default__returns_none(self):
        bp = Blueprint(_make_raw_blueprint())
        assert bp.get("missing") is None

    def test_getitem__existing_key(self):
        bp = Blueprint(_make_raw_blueprint())
        assert bp["name"] == "agent"

    def test_getitem__missing_key__raises_key_error(self):
        bp = Blueprint(_make_raw_blueprint())
        with pytest.raises(KeyError):
            _ = bp["missing"]

    def test_keys__returns_all_keys(self):
        bp = Blueprint(_make_raw_blueprint())
        assert set(bp.keys()) == {"temperature", "name"}

    def test_values__returns_deep_copy(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(key="items", type="string", value="[1, 2, 3]"),
            ]
        )
        bp = Blueprint(raw, field_types={"items": List[int]})
        vals = bp.values
        vals["items"].append(4)
        assert bp.values["items"] == [1, 2, 3]


class TestBlueprintPromptResolution:
    def test_prompt_field__resolves_to_prompt_object(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(
                    key="system_prompt", type="prompt", value="ver-111"
                ),
            ]
        )

        mock_rest = mock.Mock()
        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-1"
        version_detail.template_structure = "text"
        mock_rest.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "my-prompt"
        mock_rest.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_prompt = mock.Mock(spec=Prompt)
        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=fake_prompt,
        ):
            bp = Blueprint(
                raw,
                field_types={"system_prompt": Prompt},
                rest_client_=mock_rest,
            )

        assert bp["system_prompt"] is fake_prompt
        mock_rest.prompts.get_prompt_version_by_id.assert_called_once_with("ver-111")
        mock_rest.prompts.get_prompt_by_id.assert_called_once_with("prompt-1")

    def test_chat_prompt_field__resolves_to_chat_prompt_object(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(key="messages", type="prompt", value="ver-222"),
            ]
        )

        mock_rest = mock.Mock()
        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-2"
        version_detail.template_structure = "chat"
        mock_rest.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "chat-prompt"
        mock_rest.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_chat_prompt = mock.Mock(spec=ChatPrompt)
        with mock.patch(
            "opik.api_objects.prompt.chat.chat_prompt.ChatPrompt.from_fern_prompt_version",
            return_value=fake_chat_prompt,
        ):
            bp = Blueprint(
                raw,
                field_types={"messages": ChatPrompt},
                rest_client_=mock_rest,
            )

        assert bp["messages"] is fake_chat_prompt

    def test_base_prompt_annotation__chat_structure__resolves_to_chat_prompt(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(key="p", type="prompt", value="ver-333"),
            ]
        )

        mock_rest = mock.Mock()
        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-3"
        version_detail.template_structure = "chat"
        mock_rest.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "any-prompt"
        mock_rest.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_chat_prompt = mock.Mock(spec=ChatPrompt)
        with mock.patch(
            "opik.api_objects.prompt.chat.chat_prompt.ChatPrompt.from_fern_prompt_version",
            return_value=fake_chat_prompt,
        ):
            bp = Blueprint(
                raw,
                field_types={"p": BasePrompt},
                rest_client_=mock_rest,
            )

        assert bp["p"] is fake_chat_prompt

    def test_base_prompt_annotation__text_structure__resolves_to_prompt(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(key="p", type="prompt", value="ver-444"),
            ]
        )

        mock_rest = mock.Mock()
        version_detail = mock.Mock()
        version_detail.prompt_id = "prompt-4"
        version_detail.template_structure = "text"
        mock_rest.prompts.get_prompt_version_by_id.return_value = version_detail

        prompt_detail = mock.Mock()
        prompt_detail.name = "any-prompt"
        mock_rest.prompts.get_prompt_by_id.return_value = prompt_detail

        fake_prompt = mock.Mock(spec=Prompt)
        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=fake_prompt,
        ):
            bp = Blueprint(
                raw,
                field_types={"p": BasePrompt},
                rest_client_=mock_rest,
            )

        assert bp["p"] is fake_prompt

    def test_prompt_resolution_fails__omits_key_from_values(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(
                    key="system_prompt", type="prompt", value="ver-bad"
                ),
            ]
        )

        mock_rest = mock.Mock()
        mock_rest.prompts.get_prompt_version_by_id.side_effect = Exception(
            "network error"
        )

        bp = Blueprint(
            raw,
            field_types={"system_prompt": Prompt},
            rest_client_=mock_rest,
        )

        assert "system_prompt" not in bp.keys()

    def test_prompt_version_field__resolves_to_prompt_version_detail(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(
                    key="version", type="promptcommit", value="ver-pv-111"
                ),
            ]
        )

        mock_rest = mock.Mock()
        fake_version_detail = mock.Mock(spec=PromptVersionDetail)
        mock_rest.prompts.get_prompt_version_by_id.return_value = fake_version_detail

        bp = Blueprint(
            raw,
            field_types={"version": PromptVersionDetail},
            rest_client_=mock_rest,
        )

        assert bp["version"] is fake_version_detail
        mock_rest.prompts.get_prompt_version_by_id.assert_called_once_with("ver-pv-111")
        mock_rest.prompts.get_prompt_by_id.assert_not_called()

    def test_prompt_version_field__resolution_fails__omits_key(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(
                    key="version", type="promptcommit", value="ver-pv-bad"
                ),
            ]
        )

        mock_rest = mock.Mock()
        mock_rest.prompts.get_prompt_version_by_id.side_effect = Exception("not found")

        bp = Blueprint(
            raw,
            field_types={"version": PromptVersionDetail},
            rest_client_=mock_rest,
        )

        assert "version" not in bp.keys()

    def test_two_prompt_fields__makes_exactly_two_api_calls_per_prompt(self):
        raw = _make_raw_blueprint(
            values=[
                AgentConfigValuePublic(key="p1", type="prompt", value="ver-1"),
                AgentConfigValuePublic(key="p2", type="prompt", value="ver-2"),
            ]
        )

        mock_rest = mock.Mock()

        def _version_side_effect(vid):
            v = mock.Mock()
            v.prompt_id = f"prompt-{vid}"
            v.template_structure = "text"
            return v

        mock_rest.prompts.get_prompt_version_by_id.side_effect = _version_side_effect

        def _detail_side_effect(pid):
            d = mock.Mock()
            d.name = f"name-{pid}"
            return d

        mock_rest.prompts.get_prompt_by_id.side_effect = _detail_side_effect

        with mock.patch(
            "opik.api_objects.prompt.text.prompt.Prompt.from_fern_prompt_version",
            return_value=mock.Mock(spec=Prompt),
        ):
            Blueprint(
                raw,
                field_types={"p1": Prompt, "p2": Prompt},
                rest_client_=mock_rest,
            )

        assert mock_rest.prompts.get_prompt_version_by_id.call_count == 2
        assert mock_rest.prompts.get_prompt_by_id.call_count == 2
