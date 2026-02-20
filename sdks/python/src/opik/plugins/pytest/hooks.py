import logging
import opik._logging as _logging
from typing import List, Any, Generator, TYPE_CHECKING
from opik.types import BatchFeedbackScoreDict

from opik.api_objects import opik_client
from . import test_runs_storage, experiment_runner, summary

import pytest

if TYPE_CHECKING:
    import _pytest.terminal

LOGGER = logging.getLogger(__name__)


def _is_plugin_enabled(config: Any) -> bool:
    option_enabled = bool(getattr(getattr(config, "option", None), "opik", False))

    ini_enabled = False
    if config is not None and hasattr(config, "getini"):
        try:
            ini_enabled = bool(config.getini("opik_pytest_enabled"))
        except ValueError:
            ini_enabled = False

    active_by_collection = bool(getattr(config, "_opik_pytest_active", False))

    return option_enabled or ini_enabled or active_by_collection


def pytest_addoption(parser: "pytest.Parser") -> None:
    group = parser.getgroup("opik")
    group.addoption(
        "--opik",
        action="store_true",
        default=False,
        help="Enable Opik pytest plugin hooks.",
    )
    parser.addini(
        "opik_pytest_enabled",
        "Enable Opik pytest plugin hooks.",
        type="bool",
        default=False,
    )


def pytest_collection_modifyitems(
    session: "pytest.Session", config: "pytest.Config", items: List["pytest.Item"]
) -> None:
    del session

    if _is_plugin_enabled(config):
        return

    for item in items:
        test_obj = getattr(item, "obj", None)
        if bool(getattr(test_obj, "_opik_llm_unit", False)):
            setattr(config, "_opik_pytest_active", True)
            return


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item: "pytest.Item") -> Generator:
    """
    Write the results of each test in the session
    """
    plugin_enabled = _is_plugin_enabled(item.config)
    outcome = yield

    if not plugin_enabled:
        return

    try:
        if outcome is None:
            return
        report = outcome.get_result()
        if report.when == "call":
            item.report = report
    except Exception:
        LOGGER.debug(
            "Unexpected failure during opik pytest_runtest_makereport hook",
            exc_info=True,
        )


@_logging.convert_exception_to_log_message(
    "Unexpected failure during opik pytest_sessionfinish hook",
    logger=LOGGER,
    exc_info=True,
    logging_level=logging.ERROR,
)
def pytest_sessionfinish(session: "pytest.Session", exitstatus: Any) -> None:
    if not _is_plugin_enabled(session.config):
        return

    session_items: List["pytest.Item"] = list(getattr(session, "items", []) or [])
    llm_test_items: List["pytest.Item"] = [
        test_item
        for test_item in session_items
        if getattr(test_item, "nodeid", None) in test_runs_storage.LLM_UNIT_TEST_RUNS
    ]
    if len(llm_test_items) == 0:
        return

    try:
        traces_feedback_scores: List[BatchFeedbackScoreDict] = []
        test_items_with_reports: List["pytest.Item"] = []

        for item in llm_test_items:
            nodeid = getattr(item, "nodeid", None)
            if nodeid is None:
                continue

            report: Any = getattr(item, "report", None)
            trace_data = test_runs_storage.TEST_RUNS_TO_TRACE_DATA.get(nodeid)
            if report is None or trace_data is None:
                continue

            traces_feedback_scores.append(
                BatchFeedbackScoreDict(
                    id=trace_data.id,
                    name="Passed",
                    value=float(getattr(report, "passed", False)),
                )
            )
            test_items_with_reports.append(item)

        if len(test_items_with_reports) == 0:
            return

        client = opik_client.get_client_cached()
        try:
            client.log_traces_feedback_scores(traces_feedback_scores)
            experiment_runner.run(client=client, test_items=test_items_with_reports)
        finally:
            client.flush()
    except Exception:
        LOGGER.error(
            "Unexpected exception occured while trying to log LLM unit tests experiment results",
            exc_info=True,
        )


@_logging.convert_exception_to_log_message(
    "Unexpected exception occured while trying to print LLM unit tests summary",
    logger=LOGGER,
    exc_info=True,
    logging_level=logging.DEBUG,
)
def pytest_terminal_summary(
    terminalreporter: "_pytest.terminal.TerminalReporter",
) -> None:
    if not _is_plugin_enabled(terminalreporter.config):
        return

    reports: List[pytest.TestReport] = terminalreporter.stats.get(
        "passed", []
    ) + terminalreporter.stats.get("failed", [])

    llm_reports = [
        report
        for report in reports
        if report.nodeid in test_runs_storage.LLM_UNIT_TEST_RUNS
    ]

    if len(llm_reports) == 0:
        return

    summary.print(llm_reports)
