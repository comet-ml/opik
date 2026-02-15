from types import SimpleNamespace
from unittest import mock

from opik.plugins.pytest import hooks, test_runs_storage


def _config(*, opik_option: bool = False, ini_enabled: bool = False) -> SimpleNamespace:
    return SimpleNamespace(
        option=SimpleNamespace(opik=opik_option),
        getini=lambda _: ini_enabled,
    )


def _session(*, opik_option: bool = False, ini_enabled: bool = False) -> SimpleNamespace:
    return SimpleNamespace(config=_config(opik_option=opik_option, ini_enabled=ini_enabled))


def _terminal_reporter(
    *, opik_option: bool = False, ini_enabled: bool = False
) -> SimpleNamespace:
    return SimpleNamespace(
        config=_config(opik_option=opik_option, ini_enabled=ini_enabled),
        stats={"passed": [], "failed": []},
    )


def setup_function() -> None:
    test_runs_storage.LLM_UNIT_TEST_RUNS.clear()
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA.clear()
    test_runs_storage.TEST_RUNS_CONTENTS.clear()


def test_pytest_sessionfinish__plugin_disabled__no_client_calls(monkeypatch):
    session = _session(opik_option=False, ini_enabled=False)
    session.items = [SimpleNamespace(nodeid="a")]
    test_runs_storage.LLM_UNIT_TEST_RUNS.add("a")

    get_client_mock = mock.Mock()
    monkeypatch.setattr(hooks.opik_client, "get_client_cached", get_client_mock)

    hooks.pytest_sessionfinish(session=session, exitstatus=0)

    get_client_mock.assert_not_called()


def test_pytest_sessionfinish__missing_session_items__returns_without_error(monkeypatch):
    session = _session(opik_option=True)
    get_client_mock = mock.Mock()
    monkeypatch.setattr(hooks.opik_client, "get_client_cached", get_client_mock)

    hooks.pytest_sessionfinish(session=session, exitstatus=0)

    get_client_mock.assert_not_called()


def test_pytest_sessionfinish__item_without_report__is_ignored(monkeypatch):
    session = _session(opik_option=True)
    session.items = [SimpleNamespace(nodeid="case-1")]
    test_runs_storage.LLM_UNIT_TEST_RUNS.add("case-1")
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA["case-1"] = SimpleNamespace(id="trace-1")

    get_client_mock = mock.Mock()
    monkeypatch.setattr(hooks.opik_client, "get_client_cached", get_client_mock)

    hooks.pytest_sessionfinish(session=session, exitstatus=0)

    get_client_mock.assert_not_called()


def test_pytest_sessionfinish__valid_item__logs_scores_and_runs_experiment(monkeypatch):
    report = SimpleNamespace(passed=True)
    item = SimpleNamespace(nodeid="case-1", report=report)
    session = _session(opik_option=True)
    session.items = [item]

    test_runs_storage.LLM_UNIT_TEST_RUNS.add("case-1")
    test_runs_storage.TEST_RUNS_TO_TRACE_DATA["case-1"] = SimpleNamespace(id="trace-1")

    client = mock.Mock()
    monkeypatch.setattr(hooks.opik_client, "get_client_cached", mock.Mock(return_value=client))
    run_mock = mock.Mock()
    monkeypatch.setattr(hooks.experiment_runner, "run", run_mock)

    hooks.pytest_sessionfinish(session=session, exitstatus=0)

    client.log_traces_feedback_scores.assert_called_once()
    run_mock.assert_called_once_with(client=client, test_items=[item])
    client.flush.assert_called_once()


def test_pytest_terminal_summary__plugin_disabled__no_summary_output(monkeypatch):
    terminal_reporter = _terminal_reporter(opik_option=False, ini_enabled=False)
    print_mock = mock.Mock()
    monkeypatch.setattr(hooks.summary, "print", print_mock)

    hooks.pytest_terminal_summary(terminalreporter=terminal_reporter)

    print_mock.assert_not_called()
