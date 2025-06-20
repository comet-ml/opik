from typing import NamedTuple, List, Protocol

from opik.evaluation.metrics import score_result


class ThreadTestResult(NamedTuple):
    thread_id: str
    scores: List[score_result.ScoreResult]


class EvaluationTask(Protocol):
    def __call__(self) -> ThreadTestResult:
        pass
