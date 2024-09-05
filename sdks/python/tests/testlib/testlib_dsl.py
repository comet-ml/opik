from typing import List, Any, Optional, Dict

import dataclasses
import logging
import unittest
import mock
import deepdiff
import datetime


LOGGER = logging.getLogger(__name__)

# TODO: expand classes to have more attributes, current ones are considered to be
# a bare minimum for tests to check that traces have correct structure


@dataclasses.dataclass
class SpanModel:
    id: str
    start_time: datetime.datetime
    name: Optional[str] = None
    input: Any = None
    output: Any = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    type: str = "general"
    usage: Optional[Dict[str, Any]] = None
    end_time: Optional[datetime.datetime] = None
    spans: List["SpanModel"] = dataclasses.field(default_factory=list)
    feedback_scores: List["FeedbackScoreModel"] = dataclasses.field(
        default_factory=list
    )


@dataclasses.dataclass
class TraceModel:
    id: str
    start_time: datetime.datetime
    name: Optional[str]
    input: Any = None
    output: Any = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    end_time: Optional[datetime.datetime] = None
    spans: List["SpanModel"] = dataclasses.field(default_factory=list)
    feedback_scores: List["FeedbackScoreModel"] = dataclasses.field(
        default_factory=list
    )


@dataclasses.dataclass
class FeedbackScoreModel:
    id: str
    name: str
    value: float
    category_name: Optional[str] = None
    reason: Optional[str] = None


class _AnyButNone:
    "A helper object that compares equal to everything but None."

    def __eq__(self, other):
        if other is None:
            return False

        return True

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "<ANY_BUT_NONE>"


def prepare_difference_report(expected: Any, actual: Any) -> str:
    try:
        diff_report = deepdiff.DeepDiff(
            expected, actual, exclude_types=[_AnyButNone, mock.mock._ANY]
        ).pretty()
        return diff_report
    except Exception:
        LOGGER.debug("Failed to prepare difference report", exc_info=True)
        return ""


def assert_traces_match(trace_expected, trace_actual):
    trace_expected = trace_expected.__dict__
    trace_actual = trace_actual.__dict__

    test_case = unittest.TestCase()
    test_case.maxDiff = None

    test_case.assertDictEqual(
        trace_expected,
        trace_actual,
        msg="\n" + prepare_difference_report(trace_expected, trace_actual),
    )


ANY_BUT_NONE = _AnyButNone()
