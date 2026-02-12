import asyncio
import datetime
import inspect
import json
import types

from opik.config import OpikConfig
from opik.plugins.pytest import decorator, test_runs_storage
from opik.plugins.pytest import experiment_runner
from opik.plugins.pytest import hooks
from opik.simulation.episode import EpisodeResult, EpisodeAssertion


def test_llm_unit__missing_pytest_current_test_env__does_not_track_test_run(
    monkeypatch,
):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=True),
    )
    monkeypatch.setattr(decorator.opik, "track", lambda **kwargs: (lambda fn: fn))
    monkeypatch.delenv("PYTEST_CURRENT_TEST", raising=False)

    @decorator.llm_unit()
    def sample_test():
        return "ok"

    try:
        sample_test()
        assert test_runs_storage.LLM_UNIT_TEST_RUNS == set()
    finally:
        test_runs_storage.clear()


def test_llm_unit__extracts_nodeid_from_pytest_env__happyflow(monkeypatch):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=True),
    )
    monkeypatch.setattr(decorator.opik, "track", lambda **kwargs: (lambda fn: fn))
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_trace_data",
        lambda: types.SimpleNamespace(id="trace-node"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_span_data",
        lambda: types.SimpleNamespace(id="span-node"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_trace",
        lambda **kwargs: None,
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_span",
        lambda **kwargs: None,
    )
    monkeypatch.setenv(
        "PYTEST_CURRENT_TEST",
        "tests/test_sample.py::test_case[param] (call)",
    )

    @decorator.llm_unit()
    def sample_test():
        return "ok"

    nodeid = "tests/test_sample.py::test_case[param]"
    try:
        sample_test()
        assert nodeid in test_runs_storage.TEST_RUNS_TO_TRACE_DATA
        assert nodeid in test_runs_storage.LLM_UNIT_TEST_RUNS
    finally:
        test_runs_storage.clear()


def test_llm_unit__non_dict_values__wraps_input_expected_output_metadata__happyflow(
    monkeypatch,
):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=True),
    )
    monkeypatch.setattr(decorator.opik, "track", lambda **kwargs: (lambda fn: fn))
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_trace_data",
        lambda: types.SimpleNamespace(id="trace-wrap"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_span_data",
        lambda: types.SimpleNamespace(id="span-wrap"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_trace",
        lambda **kwargs: None,
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_span",
        lambda **kwargs: None,
    )
    monkeypatch.setenv("PYTEST_CURRENT_TEST", "tests/test_sample.py::test_case (call)")

    @decorator.llm_unit()
    def sample_test(input, expected_output, metadata):
        return None

    nodeid = "tests/test_sample.py::test_case"
    try:
        sample_test("hello", "world", "meta")
        content = test_runs_storage.TEST_RUNS_CONTENTS[nodeid]
    finally:
        test_runs_storage.clear()
        monkeypatch.delenv("PYTEST_CURRENT_TEST", raising=False)

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


def test_llm_unit__supports_async_test_functions__happyflow(monkeypatch):
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
    test_runs_storage.TEST_RUNS_EPISODES["nodeid"] = EpisodeResult(scenario_id="s1")

    test_runs_storage.clear()

    assert test_runs_storage.LLM_UNIT_TEST_RUNS == set()
    assert test_runs_storage.TEST_RUNS_TO_TRACE_DATA == {}
    assert test_runs_storage.TEST_RUNS_CONTENTS == {}
    assert test_runs_storage.TEST_RUNS_EPISODES == {}


def test_pytest_plugin_config__defaults__happyflow():
    config = OpikConfig()

    assert config.pytest_experiment_enabled is True
    assert config.pytest_experiment_dataset_name == "tests"
    assert config.pytest_experiment_name_prefix == "Test-Suite"
    assert config.pytest_passed_score_name == "Passed"
    assert config.pytest_episode_artifact_enabled is False
    assert config.pytest_episode_artifact_path == ".opik/pytest_episode_report.json"


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

        def get_or_create_dataset(self, name):
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


def test_pytest_sessionfinish__uses_plugin_config(monkeypatch):
    nodeid = "tests/test_file.py::test_case"
    item = types.SimpleNamespace(
        nodeid=nodeid, report=types.SimpleNamespace(passed=True)
    )
    session = types.SimpleNamespace(items=[item])

    test_runs_storage.LLM_UNIT_TEST_RUNS.add(nodeid)
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA[nodeid] = types.SimpleNamespace(
        id="trace-123"
    )

    monkeypatch.setattr(
        hooks.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(
            pytest_passed_score_name="Result",
            pytest_experiment_dataset_name="pytest-ds",
            pytest_experiment_name_prefix="Pytest-Run",
            pytest_episode_artifact_enabled=False,
        ),
    )

    captured = {"scores": None, "run_args": None}

    class FakeClient:
        def log_traces_feedback_scores(self, scores):
            captured["scores"] = scores

        def flush(self):
            return None

    monkeypatch.setattr(hooks.opik_client, "get_client_cached", lambda: FakeClient())

    def fake_run(client, test_items, dataset_name, experiment_name_prefix):
        captured["run_args"] = {
            "dataset_name": dataset_name,
            "experiment_name_prefix": experiment_name_prefix,
            "test_items_count": len(test_items),
        }

    monkeypatch.setattr(hooks.experiment_runner, "run", fake_run)

    hooks.pytest_sessionfinish(session=session, exitstatus=0)

    assert captured["scores"] is not None
    assert len(captured["scores"]) == 1
    assert captured["scores"][0]["name"] == "Result"
    assert captured["scores"][0]["id"] == "trace-123"

    assert captured["run_args"] == {
        "dataset_name": "pytest-ds",
        "experiment_name_prefix": "Pytest-Run",
        "test_items_count": 1,
    }


def test_llm_episode__returns_original_function_when_disabled(monkeypatch):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=False),
    )

    def f():
        return "ok"

    wrapped = decorator.llm_episode()(f)
    assert wrapped is f


def test_llm_episode__stores_episode_result(monkeypatch):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=True),
    )
    monkeypatch.setattr(decorator.opik, "track", lambda **kwargs: (lambda fn: fn))
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_trace_data",
        lambda: types.SimpleNamespace(id="trace-1"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_span_data",
        lambda: types.SimpleNamespace(id="span-1"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_trace",
        lambda **kwargs: None,
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_span",
        lambda **kwargs: None,
    )
    monkeypatch.setenv(
        "PYTEST_CURRENT_TEST", "tests/test_ep.py::test_episode[param] (call)"
    )

    @decorator.llm_episode()
    def test_episode(scenario_id):
        return {
            "scenario_id": scenario_id,
            "assertions": [{"name": "schema", "passed": True}],
        }

    nodeid = "tests/test_ep.py::test_episode[param]"
    try:
        test_episode("scenario-123")
        episode = test_runs_storage.TEST_RUNS_EPISODES[nodeid]
    finally:
        test_runs_storage.clear()
        monkeypatch.delenv("PYTEST_CURRENT_TEST", raising=False)

    assert episode.scenario_id == "scenario-123"
    assert episode.assertions[0].name == "schema"
    assert episode.assertions[0].passed is True


def test_llm_episode__extracts_scenario_id_from_positional_args__happyflow(monkeypatch):
    monkeypatch.setattr(
        decorator.config,
        "get_from_user_inputs",
        lambda: types.SimpleNamespace(pytest_experiment_enabled=True),
    )
    monkeypatch.setattr(decorator.opik, "track", lambda **kwargs: (lambda fn: fn))
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_trace_data",
        lambda: types.SimpleNamespace(id="trace-2"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "get_current_span_data",
        lambda: types.SimpleNamespace(id="span-2"),
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_trace",
        lambda **kwargs: None,
    )
    monkeypatch.setattr(
        decorator.opik_context,
        "update_current_span",
        lambda **kwargs: None,
    )
    monkeypatch.setenv("PYTEST_CURRENT_TEST", "tests/test_ep.py::test_episode (call)")

    @decorator.llm_episode()
    def test_episode(scenario_id):
        return {"assertions": [{"name": "schema", "passed": True}]}

    nodeid = "tests/test_ep.py::test_episode"
    try:
        test_episode("scenario-positional")
        episode = test_runs_storage.TEST_RUNS_EPISODES[nodeid]
    finally:
        test_runs_storage.clear()
        monkeypatch.delenv("PYTEST_CURRENT_TEST", raising=False)

    assert episode.scenario_id == "scenario-positional"


def test_pytest_sessionfinish__writes_episode_artifact__happyflow(
    monkeypatch, tmp_path
):
    nodeid = "tests/test_file.py::test_case"
    try:
        monkeypatch.chdir(tmp_path)
        item = types.SimpleNamespace(
            nodeid=nodeid, report=types.SimpleNamespace(passed=True)
        )
        session = types.SimpleNamespace(items=[item])

        test_runs_storage.LLM_UNIT_TEST_RUNS.add(nodeid)
        test_runs_storage.TEST_RUNS_TO_TRACE_DATA[nodeid] = types.SimpleNamespace(
            id="trace-episode-123"
        )
        test_runs_storage.TEST_RUNS_EPISODES[nodeid] = EpisodeResult(
            scenario_id="refund_flow_v1",
            assertions=[EpisodeAssertion(name="policy", passed=True)],
        )

        artifact_path = tmp_path / ".opik" / "episode-report.json"
        monkeypatch.setattr(
            hooks.config,
            "get_from_user_inputs",
            lambda: types.SimpleNamespace(
                pytest_passed_score_name="Result",
                pytest_experiment_dataset_name="pytest-ds",
                pytest_experiment_name_prefix="Pytest-Run",
                pytest_episode_artifact_enabled=True,
                pytest_episode_artifact_path=".opik/episode-report.json",
            ),
        )

        class FakeClient:
            def log_traces_feedback_scores(self, scores):
                return None

            def flush(self):
                return None

        monkeypatch.setattr(
            hooks.opik_client, "get_client_cached", lambda: FakeClient()
        )
        monkeypatch.setattr(hooks.experiment_runner, "run", lambda **kwargs: None)

        hooks.pytest_sessionfinish(session=session, exitstatus=0)

        assert artifact_path.exists()
        payload = json.loads(artifact_path.read_text())
        assert payload["episodes_total"] == 1
        assert payload["episodes_passed"] == 1
        assert payload["episodes_failed"] == 0
        assert payload["results"][0]["nodeid"] == nodeid
        assert payload["results"][0]["episode"]["scenario_id"] == "refund_flow_v1"
    finally:
        test_runs_storage.clear()


def test_pytest_sessionfinish__episode_artifact_skips_missing_reports__happyflow(
    monkeypatch, tmp_path
):
    nodeid = "tests/test_file.py::test_case"
    missing_nodeid = "tests/test_file.py::test_case_not_run"
    try:
        monkeypatch.chdir(tmp_path)
        item = types.SimpleNamespace(
            nodeid=nodeid, report=types.SimpleNamespace(passed=True)
        )
        session = types.SimpleNamespace(items=[item])

        test_runs_storage.LLM_UNIT_TEST_RUNS.add(nodeid)
        test_runs_storage.TEST_RUNS_TO_TRACE_DATA[nodeid] = types.SimpleNamespace(
            id="trace-episode-123"
        )
        test_runs_storage.TEST_RUNS_EPISODES[nodeid] = EpisodeResult(
            scenario_id="reported_scenario",
            assertions=[EpisodeAssertion(name="policy", passed=True)],
        )
        test_runs_storage.TEST_RUNS_EPISODES[missing_nodeid] = EpisodeResult(
            scenario_id="not_reported_scenario",
            assertions=[EpisodeAssertion(name="policy", passed=True)],
        )

        artifact_path = tmp_path / ".opik" / "episode-report.json"
        monkeypatch.setattr(
            hooks.config,
            "get_from_user_inputs",
            lambda: types.SimpleNamespace(
                pytest_passed_score_name="Result",
                pytest_experiment_dataset_name="pytest-ds",
                pytest_experiment_name_prefix="Pytest-Run",
                pytest_episode_artifact_enabled=True,
                pytest_episode_artifact_path=".opik/episode-report.json",
            ),
        )

        class FakeClient:
            def log_traces_feedback_scores(self, scores):
                return None

            def flush(self):
                return None

        monkeypatch.setattr(
            hooks.opik_client, "get_client_cached", lambda: FakeClient()
        )
        monkeypatch.setattr(hooks.experiment_runner, "run", lambda **kwargs: None)

        hooks.pytest_sessionfinish(session=session, exitstatus=0)

        assert artifact_path.exists()
        payload = json.loads(artifact_path.read_text())
        assert payload["episodes_total"] == 1
        assert payload["episodes_passed"] == 1
        assert payload["episodes_failed"] == 0
        assert len(payload["results"]) == 1
        assert payload["results"][0]["episode"]["scenario_id"] == "reported_scenario"
    finally:
        test_runs_storage.clear()


def test_pytest_sessionfinish__invalid_artifact_path__raises_value_error(monkeypatch):
    nodeid = "tests/test_file.py::test_case"
    try:
        item = types.SimpleNamespace(
            nodeid=nodeid, report=types.SimpleNamespace(passed=True)
        )
        session = types.SimpleNamespace(items=[item])
        test_runs_storage.LLM_UNIT_TEST_RUNS.add(nodeid)
        test_runs_storage.TEST_RUNS_TO_TRACE_DATA[nodeid] = types.SimpleNamespace(
            id="trace-episode-123"
        )
        test_runs_storage.TEST_RUNS_EPISODES[nodeid] = EpisodeResult(
            scenario_id="refund_flow_v1",
            assertions=[EpisodeAssertion(name="policy", passed=True)],
        )
        monkeypatch.setattr(
            hooks.config,
            "get_from_user_inputs",
            lambda: types.SimpleNamespace(
                pytest_passed_score_name="Result",
                pytest_experiment_dataset_name="pytest-ds",
                pytest_experiment_name_prefix="Pytest-Run",
                pytest_episode_artifact_enabled=True,
                pytest_episode_artifact_path="/tmp/episode-report.json",
            ),
        )

        class FakeClient:
            def log_traces_feedback_scores(self, scores):
                return None

            def flush(self):
                return None

        monkeypatch.setattr(
            hooks.opik_client, "get_client_cached", lambda: FakeClient()
        )
        monkeypatch.setattr(hooks.experiment_runner, "run", lambda **kwargs: None)

        try:
            hooks.pytest_sessionfinish(session=session, exitstatus=0)
        except ValueError as exc:
            assert "must be relative" in str(exc)
        else:
            assert False, "Expected ValueError for absolute artifact path"
    finally:
        test_runs_storage.clear()


def test_pytest_unconfigure__clears_in_memory_state():
    test_runs_storage.LLM_UNIT_TEST_RUNS.add("nodeid")
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA["nodeid"] = object()
    test_runs_storage.TEST_RUNS_CONTENTS["nodeid"] = object()
    test_runs_storage.TEST_RUNS_EPISODES["nodeid"] = EpisodeResult(scenario_id="s1")

    hooks.pytest_unconfigure(config=types.SimpleNamespace())

    assert test_runs_storage.LLM_UNIT_TEST_RUNS == set()
    assert test_runs_storage.TEST_RUNS_TO_TRACE_DATA == {}
    assert test_runs_storage.TEST_RUNS_CONTENTS == {}
    assert test_runs_storage.TEST_RUNS_EPISODES == {}
