from .. import test_result
from typing import Protocol


class EvaluationTask(Protocol):
    def __call__(self) -> test_result.TestResult:
        pass
