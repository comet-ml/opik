from opik.api_objects import trace

from . import test_run_content

LLM_UNIT_TEST_RUNS: set[str] = set()

TEST_RUNS_TO_TRACE_DATA: dict[str, trace.TraceData] = {}

TEST_RUNS_CONTENTS: dict[str, "test_run_content.TestRunContent"] = {}


def clear() -> None:
    TEST_RUNS_CONTENTS.clear()
    TEST_RUNS_TO_TRACE_DATA.clear()
