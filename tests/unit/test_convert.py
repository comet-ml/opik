
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


def test_chain_metadata_to_flat_dict__metadata_is_None__only_timestamp_data_returned():
    result = convert.chain_metadata_to_flat_dict(
        metadata=None,
        start_timestamp="start-timestamp",
        end_timestamp="end-timestamp",
        duration="the-duration"
    )

    assert result == {
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "chain_duration": "the-duration"
    }


def test_chain_metadata_to_flat_dict__everything_is_None__empty_dict_returned():
    result = convert.chain_metadata_to_flat_dict(
        metadata=None,
        start_timestamp=None,
        end_timestamp=None,
        duration=None
    )
    assert result == {}


def test_chain_metadata_to_flat_dict__happyflow():
    with Scenario() as s:
        s.flatten_dict.flatten("metadata-dict", reducer="dot") >> {"the-key": "the-value"}
        result = convert.chain_metadata_to_flat_dict(
            metadata="metadata-dict",
            start_timestamp="start-timestamp",
            end_timestamp="end-timestamp",
            duration="the-duration"
        )

    assert result == {
        "the-key": "the-value",
        "start_timestamp": "start-timestamp",
        "end_timestamp": "end-timestamp",
        "chain_duration": "the-duration"
    }