import box
import pytest
from testix import *

from comet_llm.chains import context


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(context, "state")
    patch_module(context, "convert")


def _construct(id):
    with Scenario() as s:
        s.state.get_new_id() >> id
        tested = context.Context()

    return tested


# def test_as_dict__no_groups__empty_dict_returned():
#     tested = _construct("some-id")
#     assert tested.as_dict() == {}


# def test_as_dict__some_groups_added__dict_with_groups_returned():
#     tested = _construct("some-id")
#     tested.add(Fake("group1", id="the-id-1"))
#     tested.add(Fake())