from typing import Dict, Set

from opik.api_objects import trace

from . import test_run_content

LLM_UNIT_TEST_RUNS: Set[str] = set()

TEST_RUNS_TO_TRACE_DATA: Dict[str, trace.TraceData] = {}

TEST_RUNS_CONTENTS: Dict[str, "test_run_content.TestRunContent"] = {}


def clear() -> None:
    TEST_RUNS_CONTENTS.clear()
    TEST_RUNS_TO_TRACE_DATA.clear()
