import logging
import opik._logging as _logging
from typing import List, Any, Generator, TYPE_CHECKING
from opik.types import BatchFeedbackScoreDict

from opik.api_objects import opik_client
import opik.config as config
from . import test_runs_storage, experiment_runner, summary, episode_artifact

import pytest

if TYPE_CHECKING:
    import _pytest.terminal

LOGGER = logging.getLogger(__name__)


def pytest_sessionstart(session: "pytest.Session") -> None:
    """Reset in-memory pytest plugin state before executing tests.

    This avoids leaking test run data between repeated local pytest sessions
    in the same Python process.
    """
    # Ensure no in-memory data leaks between local repeated runs in the same process.
    test_runs_storage.clear()


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item: "pytest.Item") -> Generator:
    """
    Write the results of each test in the session
    """
    outcome = yield

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


def pytest_sessionfinish(session: "pytest.Session", exitstatus: Any) -> None:
    """Log llm_* pytest results to Opik and emit optional episode artifacts.

    Skips logging when there are no tracked llm tests or when no valid report/trace
    pairs are available. Exceptions are logged and re-raised so CI can fail fast.
    """
    config_ = config.get_from_user_inputs()
    llm_test_items: List["pytest.Item"] = [
        test_item
        for test_item in session.items
        if test_item.nodeid in test_runs_storage.LLM_UNIT_TEST_RUNS
    ]
    if len(llm_test_items) == 0:
        return

    traces_feedback_scores: List[BatchFeedbackScoreDict] = []
    valid_items: List["pytest.Item"] = []

    for item in llm_test_items:
        report = getattr(item, "report", None)
        trace_data = test_runs_storage.TEST_RUNS_TO_TRACE_DATA.get(item.nodeid)

        if report is None or trace_data is None:
            continue

        valid_items.append(item)
        traces_feedback_scores.append(
            BatchFeedbackScoreDict(
                id=trace_data.id,
                name=config_.pytest_passed_score_name,
                value=float(report.passed),
            )
        )

    if len(valid_items) == 0:
        return

    try:
        client = opik_client.get_client_cached()
        client.log_traces_feedback_scores(traces_feedback_scores)

        experiment_runner.run(
            client=client,
            test_items=valid_items,
            dataset_name=config_.pytest_experiment_dataset_name,
            experiment_name_prefix=config_.pytest_experiment_name_prefix,
        )
        client.flush()

        if config_.pytest_episode_artifact_enabled:
            reports_by_nodeid = {
                item.nodeid: item.report
                for item in valid_items
                if getattr(item, "report", None) is not None
            }
            artifact_path = episode_artifact.write_episode_artifact(
                path=config_.pytest_episode_artifact_path,
                reports_by_nodeid=reports_by_nodeid,
                episodes_by_nodeid=test_runs_storage.TEST_RUNS_EPISODES,
            )
            if artifact_path is not None:
                LOGGER.info("Wrote pytest episode artifact to %s", artifact_path)
    except Exception:
        LOGGER.error(
            "Unexpected exception occured while trying to log LLM unit tests experiment results",
            exc_info=True,
        )
        raise


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


def pytest_unconfigure(config: "pytest.Config") -> None:
    # Avoid memory leakage for repeated pytest runs in long-lived processes.
    test_runs_storage.clear()
