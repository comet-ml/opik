import pytest

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
