from typing import List, Optional

import dataclasses

from . import test_case
from .metrics import score_result


@dataclasses.dataclass
class TestResult:
    test_case: test_case.TestCase
    score_results: List[score_result.ScoreResult]
    trial_id: int
    task_execution_time: Optional[float] = None
    scoring_time: Optional[float] = None
