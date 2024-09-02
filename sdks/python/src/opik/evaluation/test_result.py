from typing import List

import dataclasses

from . import test_case
from .metrics import score_result


@dataclasses.dataclass
class TestResult:
    test_case: test_case.TestCase
    score_results: List[score_result.ScoreResult]
