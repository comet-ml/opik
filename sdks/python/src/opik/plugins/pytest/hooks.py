import logging
from typing import List, Any, Generator
from opik.types import FeedbackScoreDict

from opik.api_objects import opik_client
from . import test_runs_storage, experiment_runner

import pytest
from pytest import Session, TestReport, Item

LOGGER = logging.getLogger(__name__)


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item: "Item") -> Generator:
    """
    Write the results of each test in the session
    """
    outcome = yield

    report = outcome.get_result()
    if report.when == "call":
        item.report = report


def pytest_sessionfinish(session: "Session", exitstatus: Any) -> None:
    llm_test_items: List["Item"] = [
        test_item
        for test_item in session.items
        if test_item.nodeid in test_runs_storage.LLM_UNIT_TEST_RUNS
    ]
    if len(llm_test_items) == 0:
        return

    try:
        traces_feedback_scores: List[FeedbackScoreDict] = []

        for item in llm_test_items:
            report: "TestReport" = item.report
            trace = test_runs_storage.TEST_RUNS_TRACES[item.nodeid]
            traces_feedback_scores.append(
                {"id": trace.id, "name": "Passed", "value": report.passed}
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
