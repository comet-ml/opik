from typing import List, Any, Optional, Dict

import logging
import mock
import deepdiff

from . import any_but_none

LOGGER = logging.getLogger(__name__)


def prepare_difference_report(expected: Any, actual: Any) -> str:
    try:
        diff_report = deepdiff.DeepDiff(
            expected, actual, exclude_types=[mock.mock._ANY]
        ).pretty()
        return diff_report
    except Exception:
        LOGGER.debug("Failed to prepare difference report", exc_info=True)
        return ""


def assert_equal(expected, actual):
    assert actual == expected, f"Details: {prepare_difference_report(actual, expected)}"


def assert_dicts_equal(
    dict1: Dict[str, Any],
    dict2: Dict[str, Any],
    ignore_keys: Optional[List[str]] = None,
) -> bool:
    dict1_copy, dict2_copy = {**dict1}, {**dict2}

    ignore_keys = [] if ignore_keys is None else ignore_keys

    for key in ignore_keys:
        dict1_copy.pop(key, None)
        dict2_copy.pop(key, None)

    assert dict1_copy == dict2_copy, prepare_difference_report(dict1_copy, dict2_copy)
