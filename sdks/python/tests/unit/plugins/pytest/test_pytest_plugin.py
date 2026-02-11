import asyncio
import datetime
import inspect
import types

from opik.config import OpikConfig
from opik.plugins.pytest import decorator, test_runs_storage
from opik.plugins.pytest import experiment_runner


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


def test_pytest_plugin_config_defaults():
    config = OpikConfig()

    assert config.pytest_experiment_enabled is True
    assert config.pytest_experiment_dataset_name == "tests"
    assert config.pytest_experiment_name_prefix == "Test-Suite"
    assert config.pytest_passed_score_name == "Passed"


def test_experiment_runner__uses_configurable_dataset_and_prefix(monkeypatch):
    class FakeDataset:
        def __internal_api__stream_items_as_dataclasses__(self):
            return []

        def __internal_api__insert_items_as_dataclasses__(self, items):
            self.items = items

    class FakeExperiment:
        def insert(self, experiment_items_references):
            self.refs = experiment_items_references

    class FakeClient:
        def __init__(self):
            self.dataset_name = None
            self.experiment_name = None
            self.experiment_dataset_name = None

        def get_dataset(self, name):
            raise RuntimeError("dataset does not exist")

        def create_dataset(self, name):
            self.dataset_name = name
            return FakeDataset()

        def create_experiment(self, name, dataset_name):
            self.experiment_name = name
            self.experiment_dataset_name = dataset_name
            return FakeExperiment()

        def flush(self):
            return None

    monkeypatch.setattr(
        experiment_runner.datetime_helpers,
        "local_timestamp",
        lambda: datetime.datetime(2026, 2, 11, tzinfo=datetime.timezone.utc),
    )

    client = FakeClient()
    experiment_runner.run(
        client=client,
        test_items=[],
        dataset_name="custom-tests",
        experiment_name_prefix="CI-Tests",
    )

    assert client.dataset_name == "custom-tests"
    assert client.experiment_dataset_name == "custom-tests"
    assert client.experiment_name.startswith("CI-Tests-2026-02-11T00:00:00+00:00")
