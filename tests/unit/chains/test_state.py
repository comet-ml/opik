import pytest
from testix import *

from comet_llm import exceptions
from comet_llm.chains import state


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(state, "thread_context_registry")


def _construct():
    with Scenario() as s:
        s.thread_context_registry.ThreadContextRegistry() >> Fake("registry")

        tested = state.State()

    return tested

def test_new_id__happyflow():
    tested = _construct()

    assert tested.new_id() == 1
    assert tested.new_id() == 2


def test_chain_property_chain_was_not_set__exception_raised():
    tested = _construct()

    with Scenario() as s:
        s.registry.get("global-chain") >> None
        with pytest.raises(exceptions.CometLLMException):
            tested.chain


def test_chain_property_get__happyflow():
    tested = _construct()

    with Scenario() as s:
        s.registry.get("global-chain") >> "the-chain"
        assert tested.chain == "the-chain"


def test_chain_property_set__happyflow():
    tested = _construct()

    with Scenario() as s:
        s.registry.add("global-chain", "the-chain")
        tested.chain = "the-chain"
