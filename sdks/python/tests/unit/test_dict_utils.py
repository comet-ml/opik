import pytest
import threading
from typing import Dict, List
from opik import dict_utils


@pytest.mark.parametrize(
    ("dict1,dict2,result"),
    [
        ({}, {}, {}),
        ({"a": {"b": {"c": "c-value"}}}, {}, {"a": {"b": {"c": "c-value"}}}),
        ({}, {"a": "a-value"}, {"a": "a-value"}),
        (
            {"a": {"b": "b-value"}, "c": "c-value"},
            {"a": {"d": "d-value"}},
            {"a": {"b": "b-value", "d": "d-value"}, "c": "c-value"},
        ),
        (
            {"a": {"b": "b-value"}, "c": "c-value-1"},
            {"a": {"d": "d-value"}, "c": "c-value-2"},
            {"a": {"b": "b-value", "d": "d-value"}, "c": "c-value-2"},
        ),
        (
            {"a": {"b": {"c": "c-value-1"}}},
            {"a": {"b": {"c": "c-value-2", "d": "d-value"}}},
            {"a": {"b": {"c": "c-value-2", "d": "d-value"}}},
        ),
        (
            {"a": {"b": {"c": "c-value-1"}}, "e": {"f": "f-value"}},
            {"a": {"b": {"c": "c-value-2", "d": "d-value"}}, "e": "e-value"},
            {"a": {"b": {"c": "c-value-2", "d": "d-value"}}, "e": "e-value"},
        ),
        (
            {"a": {"b": {"c": "c-value-1"}}, "e": {"f": "f-value"}},
            {"a": {"b": {"c": "c-value-2", "d": "d-value"}}, "e": "e-value"},
            {"a": {"b": {"c": "c-value-2", "d": "d-value"}}, "e": "e-value"},
        ),
    ],
)
def test_deepmerge(dict1, dict2, result):
    assert dict_utils.deepmerge(dict1, dict2) == result


def test_deepmerge__objects_copied_by_reference_only():
    unpicklable_object1 = threading.Lock()
    unpicklable_object2 = threading.Lock()

    dict1 = {"a": {"l1": unpicklable_object1}}
    dict2 = {"a": {"l2": unpicklable_object2}}

    assert dict_utils.deepmerge(dict1, dict2) == {
        "a": {"l1": unpicklable_object1, "l2": unpicklable_object2}
    }


def test_deepmerge__recursion_limit_exceeded__next_dict_is_treated_as_non_dict_value():
    dict1 = {"level1": {"level2": {"level3": {"first-dict-level4": "value"}}}}

    dict2 = {"level1": {"level2": {"level3": {"second-dict-level4": "value"}}}}

    assert dict_utils.deepmerge(dict1, dict2, max_depth=2) == {
        "level1": {"level2": {"level3": {"second-dict-level4": "value"}}}
    }


def test_deepmerge__recursion_limit_not_exceeded__merging_performed_as_usual():
    dict1 = {"level1": {"level2": {"level3": {"first-dict-level4": "value"}}}}

    dict2 = {"level1": {"level2": {"level3": {"second-dict-level4": "value"}}}}

    assert dict_utils.deepmerge(dict1, dict2, max_depth=10) == {
        "level1": {
            "level2": {
                "level3": {"second-dict-level4": "value", "first-dict-level4": "value"}
            }
        }
    }


@pytest.mark.parametrize(
    "input_dict, keys, expected_subset, expected_remaining",
    [
        # all specified keys are in the dictionary
        ({"a": 1, "b": 2, "c": 3}, ["a", "c"], {"a": 1, "c": 3}, {"b": 2}),
        # some keys in the list are not in the dictionary
        ({"a": 1, "b": 2, "c": 3}, ["a", "d"], {"a": 1}, {"b": 2, "c": 3}),
        # empty list of keys
        ({"a": 1, "b": 2, "c": 3}, [], {}, {"a": 1, "b": 2, "c": 3}),
        # no matching keys in dictionary
        ({"a": 1, "b": 2, "c": 3}, ["d", "e"], {}, {"a": 1, "b": 2, "c": 3}),
        # empty input dictionary
        ({}, ["a", "b"], {}, {}),
        # all keys in input dictionary
        ({"a": 1, "b": 2, "c": 3}, ["a", "b", "c"], {"a": 1, "b": 2, "c": 3}, {}),
    ],
)
def test_split_dict_by_keys(
    input_dict: Dict, keys: List, expected_subset: Dict, expected_remaining: Dict
):
    subset, remaining = dict_utils.split_dict_by_keys(input_dict, keys)
    assert subset == expected_subset
    assert remaining == expected_remaining
