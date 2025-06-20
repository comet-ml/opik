from collections import defaultdict
from typing import Dict, List

import pydantic

from ..metrics import score_result


class ThreadsEvaluationResult(pydantic.BaseModel):
    """Threads evaluation results with a key as thread id and value as a list of evaluation results per metric."""

    results: Dict[str, List[score_result.ScoreResult]] = defaultdict(list)
