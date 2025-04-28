from typing import List, Any, Optional, Dict, Mapping
from unittest import mock

import logging
import deepdiff


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
            and ("dict to AnyDict" not in diff_report_line)
            and ("list to AnyList" not in diff_report_line)
            and ("str to AnyStr" not in diff_report_line)
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
        f"Dict does't contain all the required keys. Dict keys: {dic.keys()}, required keys: {keys}"
    )
