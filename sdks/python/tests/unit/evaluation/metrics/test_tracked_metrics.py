import pytest

from opik.evaluation.metrics.heuristics import equals
from opik.decorator import tracker
from ....testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)

import unittest.mock


@pytest.fixture(autouse=True)
def disable_misconfigurations_detection():
    with unittest.mock.patch(
        "opik.config.OpikConfig.check_for_known_misconfigurations", return_value=False
    ):
        yield


def test_metric_equals__track_enabled__happyflow(fake_backend):
    metric = equals.Equals(name="equals_metric", track=True)

    score_result = metric.score(output="123", reference="345").__dict__

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="equals_metric",
        input={"output": "123", "reference": "345", "ignored_kwargs": {}},
        output={"output": score_result},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="equals_metric",
                input={"output": "123", "reference": "345", "ignored_kwargs": {}},
                output={"output": score_result},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_metric_equals__track_disabled__no_data_logged(fake_backend):
    metric = equals.Equals(name="equals_metric", track=False)

    metric.score(output="123", reference="345")

    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 0


def test_metric_equals__track_enabled__project_name_set__data_logged_to_the_specified_project(
    fake_backend,
):
    metric = equals.Equals(
        name="equals_metric", track=True, project_name="metric-project-name"
    )

    score_result = metric.score(output="123", reference="345").__dict__

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="equals_metric",
        input={"output": "123", "reference": "345", "ignored_kwargs": {}},
        output={"output": score_result},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name="metric-project-name",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="equals_metric",
                input={"output": "123", "reference": "345", "ignored_kwargs": {}},
                output={"output": score_result},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="metric-project-name",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_metric_equals__track_disabled__project_name_set__value_error_raised_on_instantiation():
    with pytest.raises(ValueError):
        equals.Equals(
            name="equals_metric", track=False, project_name="metric-project-name"
        )
