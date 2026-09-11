"""
Unit tests for opik.evaluation.rest_operations.log_test_result_feedback_scores.

Verifies that suite-assertion ScoreResults are routed to the new
assertion-results endpoint while regular feedback scores continue to use
the feedback-scores path.
"""

from unittest import mock

from opik.evaluation import rest_operations
from opik.evaluation.metrics import score_result


def _client_mock() -> mock.MagicMock:
    return mock.MagicMock()


class TestLogTestResultFeedbackScoresRouting:
    def test_only_regular_scores__calls_feedback_scores_only(self):
        client = _client_mock()
        results = [
            score_result.ScoreResult(name="precision", value=0.9, reason="ok"),
            score_result.ScoreResult(name="recall", value=0.8, reason="ok"),
        ]

        rest_operations.log_test_result_feedback_scores(
            client=client,
            score_results=results,
            trace_id="trace-1",
            project_name="proj-A",
        )

        client.log_traces_feedback_scores.assert_called_once()
        client.log_assertion_results.assert_not_called()
        scores = client.log_traces_feedback_scores.call_args.kwargs["scores"]
        assert {s["name"] for s in scores} == {"precision", "recall"}

    def test_only_suite_assertions__calls_assertion_results_only(self):
        client = _client_mock()
        results = [
            score_result.ScoreResult(
                name="must mention paris",
                value=True,
                reason="mentioned",
                category_name="suite_assertion",
            ),
            score_result.ScoreResult(
                name="must be polite",
                value=False,
                reason="rude tone",
                category_name="suite_assertion",
            ),
        ]

        rest_operations.log_test_result_feedback_scores(
            client=client,
            score_results=results,
            trace_id="trace-1",
            project_name="proj-A",
        )

        client.log_traces_feedback_scores.assert_not_called()
        client.log_assertion_results.assert_called_once()
        kwargs = client.log_assertion_results.call_args.kwargs
        assert kwargs["project_name"] == "proj-A"
        sent = kwargs["assertion_results"]
        assert len(sent) == 2
        assert sent[0]["id"] == "trace-1"
        assert sent[0]["name"] == "must mention paris"
        assert sent[0]["status"] == "passed"
        assert sent[0]["reason"] == "mentioned"
        assert sent[1]["status"] == "failed"

    def test_mixed__splits_suite_assertions_from_feedback_scores(self):
        client = _client_mock()
        results = [
            score_result.ScoreResult(name="precision", value=0.9),
            score_result.ScoreResult(
                name="must mention paris",
                value=True,
                category_name="suite_assertion",
            ),
        ]

        rest_operations.log_test_result_feedback_scores(
            client=client,
            score_results=results,
            trace_id="trace-1",
            project_name="proj-A",
        )

        client.log_traces_feedback_scores.assert_called_once()
        feedback_scores = client.log_traces_feedback_scores.call_args.kwargs["scores"]
        assert len(feedback_scores) == 1
        assert feedback_scores[0]["name"] == "precision"

        client.log_assertion_results.assert_called_once()
        assertion_results = client.log_assertion_results.call_args.kwargs[
            "assertion_results"
        ]
        assert len(assertion_results) == 1
        assert assertion_results[0]["name"] == "must mention paris"
        assert assertion_results[0]["status"] == "passed"

    def test_scoring_failed_records__excluded_from_both_endpoints(self):
        client = _client_mock()
        results = [
            score_result.ScoreResult(
                name="must mention paris",
                value=False,
                category_name="suite_assertion",
                scoring_failed=True,
            ),
            score_result.ScoreResult(name="precision", value=0.0, scoring_failed=True),
        ]

        rest_operations.log_test_result_feedback_scores(
            client=client,
            score_results=results,
            trace_id="trace-1",
            project_name="proj-A",
        )

        client.log_traces_feedback_scores.assert_not_called()
        client.log_assertion_results.assert_not_called()
