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

def test_get_chain__different_thread_ids__different_chain_instances():
    tested = state.State()

    tested.set_chain("thread-1", "chain-1")
    tested.set_chain("thread-2", "chain-2")

    assert tested.get_chain("thread-1") == "chain-1"
    assert tested.get_chain("thread-2") == "chain-2"


def test_get_chain__chain_not_found_for_provided_thread_id__exception_raised():
    tested = state.State()

    with pytest.raises(exceptions.CometLLMException):
        tested.get_chain("thread-id")
