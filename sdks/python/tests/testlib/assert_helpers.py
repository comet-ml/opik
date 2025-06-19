from typing import List, Any, Optional, Dict, Mapping
from unittest import mock

import logging
import deepdiff

from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)


def prepare_difference_report(expected: Any, actual: Any) -> str:
    try:
        diff = deepdiff.DeepDiff(expected, actual, exclude_types=[type(mock.ANY)])
        diff_report = diff.pretty()

        # Remove from report lines like that "X type changed from int to ANY_BUT_NONE"
        # But keep the lines like "X type changed from NoneType to ANY_BUT_NONE"
        # The rest of the lines remain.
        # Extend the list of conditions if you are adding a new Any* assertion helper
        diff_report_lines = diff_report.split("\n")
        diff_report_cleaned_lines = [
            diff_report_line
            for diff_report_line in diff_report_lines
            if (
                "NoneType to AnyButNone" in diff_report_line
                or "AnyButNone to NoneType" in diff_report_line
                or "AnyButNone" not in diff_report_line
            )
            and (
                "changed from AnyDict to dict and value changed from <ANY_DICT>"
                not in diff_report_line
            )
            and (
                "changed from AnyList to list and value changed from <ANY_LIST>"
                not in diff_report_line
            )
            and (
                "changed from AnyString to str and value changed from <ANY_STRING>"
                not in diff_report_line
            )
        ]
        diff_report_clean = "\n".join(diff_report_cleaned_lines)

        return diff_report_clean
    except Exception:
        LOGGER.debug("Failed to prepare difference report", exc_info=True)
        return "Failed to prepare difference report"


def assert_equal(expected, actual):
    """
    expected MUST be left argument so that __eq__ operators
    from our ANY* comparison helpers were called instead of __eq__ operators
    of the actual object
    """
    assert (
        expected == actual
    ), f"Details: {prepare_difference_report(actual=actual, expected=expected)}"


def assert_dicts_equal(
    dict1: Mapping[str, Any],
    dict2: Mapping[str, Any],
    ignore_keys: Optional[List[str]] = None,
) -> None:
    dict1_copy, dict2_copy = {**dict1}, {**dict2}

    ignore_keys = [] if ignore_keys is None else ignore_keys

    for key in ignore_keys:
        dict1_copy.pop(key, None)
        dict2_copy.pop(key, None)

    assert dict1_copy == dict2_copy, prepare_difference_report(dict1_copy, dict2_copy)


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
