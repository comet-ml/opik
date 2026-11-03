import pytest

import opik
from opik.plugins.pytest import decorator, test_runs_storage


@pytest.fixture(autouse=True)
def clear_test_runs_storage():
    yield
    test_runs_storage.LLM_UNIT_TEST_RUNS.clear()
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA.clear()
    test_runs_storage.TEST_RUNS_CONTENTS.clear()


def _run_test(monkeypatch, nodeid, test_func):
    monkeypatch.setenv("PYTEST_CURRENT_TEST", f"{nodeid} (call)")
    try:
        test_func()
    except BaseException:
        # pytest.skip(), pytest.xfail(), pytest.fail() and assertion failures
        # all end the test body; pytest catches them, so the test does too.
        pass


@pytest.mark.parametrize(
    "stop_test",
    [
        pytest.param(lambda: pytest.skip("not today"), id="skip"),
        pytest.param(lambda: pytest.xfail("known bug"), id="xfail"),
        pytest.param(lambda: pytest.fail("broken"), id="fail"),
        pytest.param(lambda: _assert_false(), id="assertion"),
    ],
)
def test_llm_unit__test_body_stops_early__trace_ends_and_next_test_gets_its_own(
    fake_backend, monkeypatch, stop_test
):
    @decorator.llm_unit()
    def test_stopped():
        stop_test()

    @decorator.llm_unit()
    def test_after():
        pass

    _run_test(monkeypatch, "tests/test_x.py::test_stopped", test_stopped)
    _run_test(monkeypatch, "tests/test_x.py::test_after", test_after)
    opik.flush_tracker()

    assert opik.opik_context.get_current_trace_data() is None
    assert [trace.name for trace in fake_backend.trace_trees] == [
        "test_stopped",
        "test_after",
    ]
    assert all(trace.end_time is not None for trace in fake_backend.trace_trees)

    traces = test_runs_storage.TEST_RUNS_TO_TRACE_DATA
    assert (
        traces["tests/test_x.py::test_stopped"].id
        != traces["tests/test_x.py::test_after"].id
    )


def _assert_false():
    assert False
