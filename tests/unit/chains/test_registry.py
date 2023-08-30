import pytest
import threading

from testix import *
from comet_llm.chains import thread_registry

@pytest.fixture
def mock_imports(patch_module):
    patch_module(thread_registry, "threading")

def _construct():
    with Scenario() as s:
        thread_registry.threading.Lock = threading.Lock
        tested = thread_registry.ChainThreadRegistry()
    
    return tested


def test_registry__add_and_get__different_threads_have_different_chains(mock_imports):
    tested = _construct()

    with Scenario() as s:
        s.threading.get_ident() >> "thread-1"
        tested.add("chain-from-thread-1")

        s.threading.get_ident() >> "thread-2"
        tested.add("chain-from-thread-2")

    with Scenario() as s:
        s.threading.get_ident() >> "thread-1"
        assert tested.get() == "chain-from-thread-1"

        s.threading.get_ident() >> "thread-2"
        assert tested.get() == "chain-from-thread-2"