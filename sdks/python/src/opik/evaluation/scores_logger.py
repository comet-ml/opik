from typing import List
from opik.types import FeedbackScoreDict

from opik.api_objects import opik_client
from . import test_result


def log_scores(
    client: opik_client.Opik, test_results: List[test_result.TestResult]
) -> None:
    all_trace_scores: List[FeedbackScoreDict] = []

    for result in test_results:
        for score_result in result.score_results:
            if score_result.scoring_failed:
                continue

            trace_score = FeedbackScoreDict(
                id=result.test_case.trace_id,
                name=score_result.name,
                value=score_result.value,
                reason=score_result.reason,
            )
            all_trace_scores.append(trace_score)

    client.log_traces_feedback_scores(scores=all_trace_scores)
