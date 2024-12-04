from opik.api_objects import experiment
import pytest
import types


def fake_prompt():
    return types.SimpleNamespace(
        __internal_api__version_id__="some-prompt-version-id",
        prompt="some-prompt-value",
    )


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
