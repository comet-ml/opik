from typing import List

import pydantic

from ..metrics import score_result


class ThreadEvaluationResult(pydantic.BaseModel):
    """Evaluation result for a particular thread."""

    thread_id: str
    scores: List[score_result.ScoreResult] = pydantic.Field(default_factory=list)


class ThreadsEvaluationResult(pydantic.BaseModel):
    """Threads evaluation results"""

    results: List[ThreadEvaluationResult] = pydantic.Field(default_factory=list)
