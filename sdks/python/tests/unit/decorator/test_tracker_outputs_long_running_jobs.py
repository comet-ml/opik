import datetime

import pytest

from opik.decorator import tracker
from ...testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)


@pytest.mark.parametrize(
    "fake_backend_with_patched_environment",
    [{"OPIK_LOG_START_TRACE_SPAN": "True"}],
    indirect=True,
)
def test_track__trace_logged_at_start_and_end(fake_backend_with_patched_environment):
    @tracker.track
    def f(x):
        return "the-output"

    f("the-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = [
        TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={"x": "the-input"},
            start_time=ANY_BUT_NONE,
            project_name="Default Project",
            last_updated_at=ANY_BUT_NONE,
        ),
        TraceModel(
            id=ANY_BUT_NONE,
            start_time=ANY_BUT_NONE,
            name="f",
            project_name="Default Project",
            input={"x": "the-input"},
            output={"output": "the-output"},
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    name="f",
                    input={"x": "the-input"},
                    output={"output": "the-output"},
                    end_time=ANY_BUT_NONE,
                    project_name="Default Project",
                )
            ],
        ),
    ]

    trace_trees = fake_backend_with_patched_environment.trace_trees
    assert len(trace_trees) == 2

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees)

    assert trace_trees[0].id == trace_trees[1].id
    assert trace_trees[0].last_updated_at < trace_trees[1].last_updated_at

    assert isinstance(trace_trees[1].spans[0].last_updated_at, datetime.datetime)
