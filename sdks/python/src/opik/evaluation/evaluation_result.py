from typing import List

import dataclasses

from . import test_result


@dataclasses.dataclass
class EvaluationResult:
    experiment_id: str
    experiment_name: str
    test_results: List[test_result.TestResult]
