import threading

import pytest
from testix import *

from comet_llm.chains import thread_context_registry


@pytest.fixture
def mock_imports(patch_module):
    patch_module(thread_context_registry, "threading")

def _construct():
    with Scenario() as s:
        thread_context_registry.threading.Lock = threading.Lock
        tested = thread_context_registry.ThreadContextRegistry()

    return tested


def test_registry__add_and_get__different_threads_have_different_chains(mock_imports):
    tested = _construct()

    with Scenario() as s:
        s.threading.get_ident() >> "thread-1"
        tested.add("key", "value-from-thread-1")

        s.threading.get_ident() >> "thread-2"
        tested.add("key", "value-from-thread-2")

    with Scenario() as s:
        s.threading.get_ident() >> "thread-1"
        assert tested.get("key") == "value-from-thread-1"

        s.threading.get_ident() >> "thread-2"
        assert tested.get("key") == "value-from-thread-2"