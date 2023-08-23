
import pytest
from testix import *

from comet_llm import convert


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(convert, "flatten_dict")


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