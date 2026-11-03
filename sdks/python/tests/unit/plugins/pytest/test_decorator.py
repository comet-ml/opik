import pytest

import opik
from opik import tracing_runtime_config
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
        return test_func()
    except (
        pytest.skip.Exception,
        pytest.xfail.Exception,
        pytest.fail.Exception,
        AssertionError,
    ):
        # These end the test body; pytest catches them, so the test does too.
        return None


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


@pytest.mark.parametrize(
    "returned, expected_output",
    [
        pytest.param({"answer": 42}, {"answer": 42}, id="dict"),
        pytest.param(42, {"output": 42}, id="not-dict"),
    ],
)
def test_llm_unit__test_returns_value__logged_as_trace_output(
    fake_backend, monkeypatch, returned, expected_output
):
    @decorator.llm_unit()
    def test_returning():
        return returned

    assert (
        _run_test(monkeypatch, "tests/test_x.py::test_returning", test_returning)
        == returned
    )
    opik.flush_tracker()

    assert fake_backend.trace_trees[0].output == expected_output


def test_llm_unit__tracing_disabled__test_runs_without_trace_or_experiment(
    fake_backend, monkeypatch
):
    @decorator.llm_unit()
    def test_untraced():
        return 42

    tracing_runtime_config.set_tracing_active(False)
    try:
        assert (
            _run_test(monkeypatch, "tests/test_x.py::test_untraced", test_untraced)
            == 42
        )
    finally:
        tracing_runtime_config.reset_tracing_to_config_default()
    opik.flush_tracker()

    assert fake_backend.trace_trees == []
    assert test_runs_storage.LLM_UNIT_TEST_RUNS == set()
