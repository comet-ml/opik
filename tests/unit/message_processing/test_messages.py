import mock

from comet_llm import experiment_info
from comet_llm.message_processing import messages


def test_message_dict_conversion__api_key_excluded_in_to_dict__api_key_included_in_from_dict():
    experiment_info_ = experiment_info.ExperimentInfo(
        api_key="api-key",
        workspace="the-workspace",
        project_name="project-name"
    )

    prompt_message = messages.PromptMessage(
        id="the-id",
        experiment_info_=experiment_info_,
        prompt_asset_data={"asset-key": "asset-value"},
        duration=1000,
        metadata={"metadata-key": "metadata-value"},
        tags=["tag1", "tag2"]
    )

    dict_message = prompt_message.to_dict()

    assert dict_message == {
        "id": "the-id",
        "experiment_info_": {"workspace": "the-workspace", "project_name": "project-name"},
        "prompt_asset_data": {"asset-key": "asset-value"},
        "duration": 1000,
        "metadata": {"metadata-key": "metadata-value"},
        "tags": ["tag1", "tag2"],
        "VERSION": messages.PromptMessage.VERSION
    }

    assert prompt_message == messages.PromptMessage.from_dict(dict_message, api_key="api-key")


def test_message_dict_conversion__version_1_dict__converted_to_version_2_message_with_id_generation():
    experiment_info_ = experiment_info.ExperimentInfo(
        api_key="api-key",
        workspace="the-workspace",
        project_name="project-name"
    )

    prompt_message_v2 = messages.PromptMessage(
        id=mock.ANY,
        experiment_info_=experiment_info_,
        prompt_asset_data={"asset-key": "asset-value"},
        duration=1000,
        metadata={"metadata-key": "metadata-value"},
        tags=["tag1", "tag2"]
    )

    v1_message_dict = {
        "experiment_info_": {"workspace": "the-workspace", "project_name": "project-name"},
        "prompt_asset_data": {"asset-key": "asset-value"},
        "duration": 1000,
        "metadata": {"metadata-key": "metadata-value"},
        "tags": ["tag1", "tag2"],
        "VERSION": 1
    }

    assert prompt_message_v2 == messages.PromptMessage.from_dict(v1_message_dict, api_key="api-key")