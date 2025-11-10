import types

import pytest

from opik.api_objects import experiment
from tests.conftest import random_chars


def fake_prompt(with_postfix: bool = False):
    postfix = random_chars()

    def to_info_dict():
        return {
            "name": fake_prompt_obj.name,
            "version": {
                "template": fake_prompt_obj.prompt,
            },
        }

    fake_prompt_obj = types.SimpleNamespace(
        __internal_api__version_id__="some-prompt-version-id",
        prompt="some-prompt-value",
        name="some-prompt-name",
        to_info_dict=to_info_dict,
    )

    if with_postfix:
        fake_prompt_obj.prompt += postfix
        fake_prompt_obj.__internal_api__version_id__ += postfix
        fake_prompt_obj.name += postfix

    return fake_prompt_obj


@pytest.mark.parametrize(
    argnames="input_kwargs,expected",
    argvalues=[
        (
            {"experiment_config": None, "prompts": None},
            {"metadata": None, "prompt_versions": None},
        ),
        (
            {"experiment_config": {}, "prompts": None},
            {"metadata": None, "prompt_versions": None},
        ),
        (
            {"experiment_config": None, "prompts": [fake_prompt()]},
            {
                "metadata": {"prompts": {"some-prompt-name": "some-prompt-value"}},
                "prompt_versions": [{"id": "some-prompt-version-id"}],
            },
        ),
        (
            {"experiment_config": {}, "prompts": [fake_prompt()]},
            {
                "metadata": {"prompts": {"some-prompt-name": "some-prompt-value"}},
                "prompt_versions": [{"id": "some-prompt-version-id"}],
            },
        ),
        (
            {"experiment_config": {"some-key": "some-value"}, "prompts": None},
            {"metadata": {"some-key": "some-value"}, "prompt_versions": None},
        ),
        (
            {
                "experiment_config": "NOT-DICT-VALUE-THAT-WILL-BE-IGNORED-AND-REPLACED-WITH-DICT-WITH-PROMPT",
                "prompts": [fake_prompt()],
            },
            {
                "metadata": {"prompts": {"some-prompt-name": "some-prompt-value"}},
                "prompt_versions": [{"id": "some-prompt-version-id"}],
            },
        ),
    ],
)
def test_experiment_build_metadata_from_prompt_versions(input_kwargs, expected):
    metadata, prompt_versions = experiment.build_metadata_and_prompt_versions(
        **input_kwargs
    )

    assert metadata == expected["metadata"]
    assert prompt_versions == expected["prompt_versions"]


def test_check_prompt_args_with_none_arguments():
    result = experiment.handle_prompt_args(prompt=None, prompts=None)
    assert result is None


def test_check_prompt_args_with_none_and_empty_list():
    result = experiment.handle_prompt_args(prompt=None, prompts=[])
    assert result is None


def test_check_prompt_args_with_single_prompt():
    mock_prompt = fake_prompt(with_postfix=True)
    result = experiment.handle_prompt_args(prompt=mock_prompt, prompts=None)
    assert isinstance(result, list)
    assert len(result) == 1
    assert result[0] == mock_prompt


def test_check_prompt_args_with_prompts_list():
    mock_prompt_1 = fake_prompt(with_postfix=True)
    mock_prompt_2 = fake_prompt(with_postfix=True)
    prompts = [mock_prompt_1, mock_prompt_2]
    result = experiment.handle_prompt_args(prompt=None, prompts=prompts)
    assert result == prompts


def test_check_prompt_args_with_both_prompt_and_prompts():
    mock_prompt = fake_prompt(with_postfix=True)
    mock_prompt_list = [
        fake_prompt(with_postfix=True),
        fake_prompt(with_postfix=True),
    ]
    result = experiment.handle_prompt_args(prompt=mock_prompt, prompts=mock_prompt_list)
    assert isinstance(result, list)
    assert result == mock_prompt_list
