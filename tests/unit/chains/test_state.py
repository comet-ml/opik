import box
import pytest
from testix import *

from comet_llm import exceptions
from comet_llm.chains import state


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


def test_chain_exists__chain_was_not_set__returned_False():
    tested = state.State()

    assert tested.chain_exists() is False


def test_chain_exists__chain_was_set__returned_True():
    tested = state.State()
    tested.chain = "the-chain"

    assert tested.chain_exists() is True



def test_chain_property__happyflow():
    tested = state.State()

    tested.chain = "the-chain"
    assert tested.chain == "the-chain"