
import pytest
from testix import *

from comet_llm import convert


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(convert, "flatten_dict")


def test_call_data_to_dict():
    result = convert.call_data_to_dict(
        prompt="the-prompt",
        outputs="the-outputs",
        metadata="the-metadata",
        prompt_template="prompt-template",
        prompt_template_variables="prompt-template-variables",
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "id": 1,
        "category": "llm-call",
        "name": "llm-call-1",
        "inputs": {
            "final_prompt": "the-prompt",
            "prompt_template": "prompt-template",
            "prompt_template_variables": "prompt-template-variables"
        },
        "outputs": {"output": "the-outputs"},
        "duration": "the-duration",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "metadata": "the-metadata",
        "parent_ids": []
    }


def test_chain_metadata_to_flat_parameters__happyflow():
    with Scenario() as s:
        s.flatten_dict.flatten("metadata-dict", reducer="dot") >> {"the-key": "the-value"}

        assert convert.chain_metadata_to_flat_parameters("metadata-dict") == {"the-key": "the-value"}


def test_chain_metadata_to_flat_parameters__everything_is_None__empty_dict_returned():
    assert convert.chain_metadata_to_flat_parameters(metadata=None) == {}


def test_chain_metadata_to_flat_parameters__items_with_None_value_not_included_to_result():
    with Scenario() as s:
        s.flatten_dict.flatten("metadata-dict", reducer="dot") >> {"the-key": "the-value", "the-key2": None}

        assert convert.chain_metadata_to_flat_parameters("metadata-dict") == {"the-key": "the-value"}