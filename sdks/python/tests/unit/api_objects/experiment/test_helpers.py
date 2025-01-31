import types
from typing import cast

import pytest

from opik import Prompt
from opik.api_objects import experiment
from tests.testlib.util_helpers import random_string


def fake_prompt():
    postfix = random_string()
    fake_prompt_obj = types.SimpleNamespace(
        __internal_api__version_id__=f"some-prompt-version-id-{postfix}",
        prompt=f"some-prompt-value-{postfix}",
    )

    return cast(fake_prompt_obj, Prompt)


@pytest.mark.parametrize(
    argnames="input_kwargs,expected",
    argvalues=[
        (
            {"experiment_config": None, "prompt": None},
            {"metadata": None, "prompt_version": None},
        ),
        (
            {"experiment_config": {}, "prompt": None},
            {"metadata": None, "prompt_version": None},
        ),
        (
            {"experiment_config": None, "prompt": fake_prompt()},
            {
                "metadata": {"prompt": "some-prompt-value"},
                "prompt_version": {"id": "some-prompt-version-id"},
            },
        ),
        (
            {"experiment_config": {}, "prompt": fake_prompt()},
            {
                "metadata": {"prompt": "some-prompt-value"},
                "prompt_version": {"id": "some-prompt-version-id"},
            },
        ),
        (
            {"experiment_config": {"some-key": "some-value"}, "prompt": None},
            {"metadata": {"some-key": "some-value"}, "prompt_version": None},
        ),
        (
            {
                "experiment_config": "NOT-DICT-VALUE-THAT-WILL-BE-IGNORED-AND-REPLACED-WITH-DICT-WITH-PROMPT",
                "prompt": fake_prompt(),
            },
            {
                "metadata": {"prompt": "some-prompt-value"},
                "prompt_version": {"id": "some-prompt-version-id"},
            },
        ),
    ],
)
def test_experiment_build_metadata_from_prompt_version(input_kwargs, expected):
    metadata, prompt_version = experiment.build_metadata_and_prompt_version(
        **input_kwargs
    )

    assert metadata == expected["metadata"]
    assert prompt_version == expected["prompt_version"]


def test_check_prompt_args_with_none_arguments():
    result = experiment.check_prompt_args(prompt=None, prompts=None)
    assert result is None


def test_check_prompt_args_with_none_and_empty_list():
    result = experiment.check_prompt_args(prompt=None, prompts=[])
    assert result is None


def test_check_prompt_args_with_single_prompt():
    mock_prompt = fake_prompt()
    result = experiment.check_prompt_args(prompt=mock_prompt, prompts=None)
    assert isinstance(result, list)
    assert len(result) == 1
    assert result[0] == mock_prompt


def test_check_prompt_args_with_prompts_list():
    mock_prompt_1 = fake_prompt()
    mock_prompt_2 = fake_prompt()
    prompts = [mock_prompt_1, mock_prompt_2]
    result = experiment.check_prompt_args(prompt=None, prompts=prompts)
    assert result == prompts


def test_check_prompt_args_with_both_prompt_and_prompts():
    mock_prompt = fake_prompt()
    mock_prompt_list = [
        fake_prompt(),
        fake_prompt(),
    ]
    result = experiment.check_prompt_args(prompt=mock_prompt, prompts=mock_prompt_list)
    assert isinstance(result, list)
    assert result == mock_prompt_list
