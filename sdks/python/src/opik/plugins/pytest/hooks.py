import logging
import opik._logging as _logging
from typing import List, Any, Generator, TYPE_CHECKING
from opik.types import FeedbackScoreDict

from opik.api_objects import opik_client
from . import test_runs_storage, experiment_runner, summary

import pytest

if TYPE_CHECKING:
    import _pytest.terminal

LOGGER = logging.getLogger(__name__)


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item: "pytest.Item") -> Generator:
    """
    Write the results of each test in the session
    """
    outcome = yield

    try:
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
    llm_test_items: List["pytest.Item"] = [
        test_item
        for test_item in session.items
        if test_item.nodeid in test_runs_storage.LLM_UNIT_TEST_RUNS
    ]
    if len(llm_test_items) == 0:
        return

    try:
        traces_feedback_scores: List[FeedbackScoreDict] = []

        for item in llm_test_items:
            report: "pytest.TestReport" = item.report
            trace_id = test_runs_storage.TEST_RUNS_TO_TRACE_DATA[item.nodeid].id
            traces_feedback_scores.append(
                {"id": trace_id, "name": "Passed", "value": report.passed}
            )

        client = opik_client.get_client_cached()
        client.log_traces_feedback_scores(traces_feedback_scores)

        experiment_runner.run(client=client, test_items=llm_test_items)
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
