from unittest import mock

from opik.evaluation import rest_operations
from opik.evaluation.metrics import score_result


def test_log_test_result_feedback_scores__maps_failure_fields() -> None:
    client = mock.MagicMock()

    score_results = [
        score_result.ScoreResult(
            name="quality_and_tone",
            value=0.82,
            reason="good structure",
            metadata={"judge": "g-eval"},
            scoring_failed=False,
        ),
        score_result.ScoreResult(
            name="quality_and_tone",
            value=0.0,
            reason="non-finite score: nan",
            metadata={"_error_type": "ValueError"},
            scoring_failed=True,
        ),
    ]

    rest_operations.log_test_result_feedback_scores(
        client=client,
        score_results=score_results,
        trace_id="trace-1",
        project_name="proj",
    )

    client.log_traces_feedback_scores.assert_called_once_with(
        scores=[
            {
                "id": "trace-1",
                "name": "quality_and_tone",
                "value": 0.82,
                "reason": "good structure",
                "metadata": {"judge": "g-eval"},
                "error": 0,
                "error_reason": None,
            },
            {
                "id": "trace-1",
                "name": "quality_and_tone",
                "value": 0.0,
                "reason": None,
                "metadata": {"_error_type": "ValueError"},
                "error": 1,
                "error_reason": "non-finite score: nan",
            },
        ],
        project_name="proj",
    )
