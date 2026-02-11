from typing import Dict, Set

from opik.api_objects import trace
from opik.simulation.episode import EpisodeResult

from . import test_run_content

LLM_UNIT_TEST_RUNS: Set[str] = set()

TEST_RUNS_TO_TRACE_DATA: Dict[str, trace.TraceData] = {}

TEST_RUNS_CONTENTS: Dict[str, "test_run_content.TestRunContent"] = {}
TEST_RUNS_EPISODES: Dict[str, EpisodeResult] = {}


def clear() -> None:
    LLM_UNIT_TEST_RUNS.clear()
    TEST_RUNS_CONTENTS.clear()
    TEST_RUNS_TO_TRACE_DATA.clear()
    TEST_RUNS_EPISODES.clear()
