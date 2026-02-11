import asyncio
import inspect
import types

from opik.plugins.pytest import decorator, test_runs_storage


def test_get_test_nodeid__fallback_when_env_missing(monkeypatch):
    monkeypatch.delenv("PYTEST_CURRENT_TEST", raising=False)

    assert decorator._get_test_nodeid() == "<unknown_test>"


def test_get_test_nodeid__extracts_nodeid(monkeypatch):
    monkeypatch.setenv(
        "PYTEST_CURRENT_TEST",
        "tests/test_sample.py::test_case[param] (call)",
    )

    assert decorator._get_test_nodeid() == "tests/test_sample.py::test_case[param]"


def test_get_test_run_content__wraps_non_dict_values(monkeypatch):
    monkeypatch.setenv("PYTEST_CURRENT_TEST", "tests/test_sample.py::test_case (call)")

    def sample_test(input, expected_output, metadata):
        return None

    content = decorator._get_test_run_content(
        func=sample_test,
        args=("hello", "world", "meta"),
        kwargs={},
        argnames_mapping={
            "input": "input",
            "expected_output": "expected_output",
            "metadata": "metadata",
        },
    )

    assert content.input == {
        "test_name": "tests/test_sample.py::test_case",
        "input": "hello",
    }
    assert content.expected_output == {"expected_output": "world"}
    assert content.metadata == {"metadata": "meta"}


def test_llm_unit__returns_original_function_when_disabled(monkeypatch):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=False),
    )

    def f():
        return "ok"

    wrapped = decorator.llm_unit()(f)
    assert wrapped is f


def test_llm_unit__supports_async_test_functions(monkeypatch):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=True),
    )
    monkeypatch.setattr(decorator.opik, "track", lambda **kwargs: (lambda fn: fn))

    async def f(value):
        return value + 1

    wrapped = decorator.llm_unit()(f)

    assert inspect.iscoroutinefunction(wrapped)
    assert asyncio.run(wrapped(41)) == 42


def test_test_runs_storage_clear__clears_all_storages():
    test_runs_storage.LLM_UNIT_TEST_RUNS.add("nodeid")
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA["nodeid"] = object()
    test_runs_storage.TEST_RUNS_CONTENTS["nodeid"] = object()

    test_runs_storage.clear()

    assert test_runs_storage.LLM_UNIT_TEST_RUNS == set()
    assert test_runs_storage.TEST_RUNS_TO_TRACE_DATA == {}
    assert test_runs_storage.TEST_RUNS_CONTENTS == {}
