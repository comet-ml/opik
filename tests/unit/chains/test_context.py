import box
import pytest
from testix import *

from comet_llm.chains import context


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(context, "state")
    patch_module(context, "convert")


def test_current__no_spans__empty_list_returned():
    tested = context.Context()
    assert tested.current() == []


def test_current__spans_added__span_ids_returned():
    tested = context.Context()
    tested.add("span-id-1")
    tested.add("span-id-2")
    assert tested.current() == ["span-id-1", "span-id-2"]

def test_pop__no_spans_added__nothing_done():
    tested = context.Context()

    assert tested.current() == []
    tested.pop()
    assert tested.current() == []

def test_current__spans_added_and_popped__span_ids_returned_without_popped_one():
    tested = context.Context()
    tested.add("span-id-1")
    tested.add("span-id-2")
    tested.pop()

    assert tested.current() == ["span-id-1"]
