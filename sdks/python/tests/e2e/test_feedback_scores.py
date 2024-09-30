from typing import List
import opik
from opik.types import FeedbackScoreDict
from . import verifiers


def test_feedbacks_are_logged_via_trace_and_span__happyflow(opik_client: opik.Opik):
    trace = opik_client.trace(
        name="trace-name",
    )
    trace.log_feedback_score(
        "trace-metric-1", value=0.5, category_name="category-1", reason="some-reason-1"
    )
    trace.log_feedback_score(
        "trace-metric-2", value=0.95, category_name="category-2", reason="some-reason-2"
    )

    span = trace.span(
        name="span-name",
    )
    span.log_feedback_score(
        "span-metric-1", value=0.75, category_name="category-3", reason="some-reason-3"
    )
    span.log_feedback_score(
        "span-metric-2", value=0.25, category_name="category-4", reason="some-reason-4"
    )

    opik_client.flush()

    EXPECTED_TRACE_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": trace.id,
            "name": "trace-metric-1",
            "value": 0.5,
            "category_name": "category-1",
            "reason": "some-reason-1",
        },
        {
            "id": trace.id,
            "name": "trace-metric-2",
            "value": 0.95,
            "category_name": "category-2",
            "reason": "some-reason-2",
        },
    ]

    EXPECTED_SPAN_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": span.id,
            "name": "span-metric-1",
            "value": 0.75,
            "category_name": "category-3",
            "reason": "some-reason-3",
        },
        {
            "id": span.id,
            "name": "span-metric-2",
            "value": 0.25,
            "category_name": "category-4",
            "reason": "some-reason-4",
        },
    ]

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="trace-name",
        feedback_scores=EXPECTED_TRACE_FEEDBACK_SCORES,
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        trace_id=span.trace_id,
        parent_span_id=None,
        name="span-name",
        feedback_scores=EXPECTED_SPAN_FEEDBACK_SCORES,
    )


def test_feedbacks_are_logged_via_client__happyflow(opik_client: opik.Opik):
    trace = opik_client.trace(name="trace-name-1")
    span = trace.span(name="span-name-1")

    EXPECTED_TRACE_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": trace.id,
            "name": "trace-metric-1",
            "value": 0.5,
            "category_name": "category-1",
            "reason": "some-reason-1",
        },
        {
            "id": trace.id,
            "name": "trace-metric-2",
            "value": 0.95,
            "category_name": "category-2",
            "reason": "some-reason-2",
        },
    ]

    EXPECTED_SPAN_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": span.id,
            "name": "span-metric-1",
            "value": 0.75,
            "category_name": "category-3",
            "reason": "some-reason-3",
        },
        {
            "id": span.id,
            "name": "span-metric-2",
            "value": 0.25,
            "category_name": "category-4",
            "reason": "some-reason-4",
        },
    ]

    opik_client.log_spans_feedback_scores(scores=EXPECTED_SPAN_FEEDBACK_SCORES)
    opik_client.log_traces_feedback_scores(scores=EXPECTED_TRACE_FEEDBACK_SCORES)

    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="trace-name-1",
        feedback_scores=EXPECTED_TRACE_FEEDBACK_SCORES,
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        trace_id=span.trace_id,
        parent_span_id=None,
        name="span-name-1",
        feedback_scores=EXPECTED_SPAN_FEEDBACK_SCORES,
    )
