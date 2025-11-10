from typing import List, Any, Optional, Dict, Mapping

import logging
import pytest_deepassert

from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)


def assert_equal(expected: Any, actual: Any) -> None:
    __tracebackhide__ = True

    pytest_deepassert.equal(expected, actual)


def assert_dicts_equal(
    dict1: Mapping[str, Any],
    dict2: Mapping[str, Any],
    ignore_keys: Optional[List[str]] = None,
) -> None:
    __tracebackhide__ = True

    dict1_copy, dict2_copy = {**dict1}, {**dict2}

    ignore_keys = [] if ignore_keys is None else ignore_keys

    for key in ignore_keys:
        dict1_copy.pop(key, None)
        dict2_copy.pop(key, None)

    pytest_deepassert.equal(dict1_copy, dict2_copy)


def assert_dict_has_keys(dic: Dict[str, Any], keys: List[str]) -> None:
    dict_has_keys = all(key in dic for key in keys)

    if dict_has_keys:
        return

    raise AssertionError(
        f"Dict doesn't contain all the required keys. Dict keys: {dic.keys()}, required keys: {keys}"
    )


def assert_dict_keys_in_list(dic: Dict[str, Any], keys: List[str]) -> None:
    """
    Asserts that all keys in the dictionary are present in the given list.

    Args:
        dic: The dictionary whose keys need to be checked
        keys: The list of allowed keys

    Raises:
        AssertionError: If any key in the dictionary is not in the provided list
    """
    invalid_keys = [key for key in dic.keys() if key not in keys]

    if len(invalid_keys) == 0:
        return

    raise AssertionError(
        f"Dict contains keys that are not in the allowed list. Invalid keys: {invalid_keys}, allowed keys: {keys}"
    )


def assert_score_result(
    result: score_result.ScoreResult, include_reason: bool = True
) -> None:
    assert result.scoring_failed is False
    assert isinstance(result.value, float)
    assert 0.0 <= result.value <= 1.0
    if include_reason:
        assert isinstance(result.reason, str)
        assert len(result.reason) > 0
