from testix import *

import box
import pytest

from comet_llm.chains import state
from comet_llm import exceptions

@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    pass

def test_new_id__happyflow():
    tested = state.State()

    assert tested.new_id() == 1
    assert tested.new_id() == 2


def test_chain_property_chain_was_not_set__exception_raised():
    tested = state.State()

    with pytest.raises(exceptions.CometLLMException):
        tested.chain


def test_chain_property__happyflow():
    tested = state.State()

    tested.chain = "the-chain"
    assert tested.chain == "the-chain"