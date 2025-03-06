from typing import List
import opik
from opik import opik_context
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


def test_feedbacks_are_logged_via_trace_and_span__and_deleted(opik_client: opik.Opik):
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

    opik_client.delete_trace_feedback_score(trace.id, "trace-metric-1")
    opik_client.delete_span_feedback_score(span.id, "span-metric-1")
    opik_client.delete_span_feedback_score(span.id, "span-metric-2")

    opik_client.flush()

    EXPECTED_TRACE_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": trace.id,
            "name": "trace-metric-2",
            "value": 0.95,
            "category_name": "category-2",
            "reason": "some-reason-2",
        },
    ]

    EXPECTED_SPAN_FEEDBACK_SCORES = None

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


def test_feedback_scores_added_via_update_current_span_and_trace__project_specified_manually__feedback_scores_correctly_attached_to_span_and_trace(
    opik_client: opik.Opik,
):
    ID_STORAGE = {}

    @opik.track
    def f_inner():
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span_data().id
        opik_context.update_current_span(
            feedback_scores=[
                {
                    "name": "inner-span-feedback-score",
                    "value": 0.25,
                    "category_name": "span-score-category",
                    "reason": "span-score-reason",
                }
            ]
        )

    @opik.track(project_name="manually-specified-project")
    def f_outer():
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id
        f_inner()
        opik_context.update_current_trace(
            feedback_scores=[
                {
                    "name": "trace-feedback-score",
                    "value": 0.75,
                    "category_name": "trace-score-category",
                    "reason": "trace-score-reason",
                },
                {
                    "name": "trace-feedback-score-2",
                    "value": 0.5,
                    "category_name": "trace-score-category-2",
                    "reason": "trace-score-reason-2",
                },
            ]
        )

    f_outer()
    opik.flush_tracker()

    EXPECTED_INNER_SPAN_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": ID_STORAGE["f_inner-span-id"],
            "name": "inner-span-feedback-score",
            "value": 0.25,
            "category_name": "span-score-category",
            "reason": "span-score-reason",
        },
    ]

    EXPECTED_TRACE_FEEDBACK_SCORES: List[FeedbackScoreDict] = [
        {
            "id": ID_STORAGE["f_outer-trace-id"],
            "name": "trace-feedback-score",
            "value": 0.75,
            "category_name": "trace-score-category",
            "reason": "trace-score-reason",
        },
        {
            "id": ID_STORAGE["f_outer-trace-id"],
            "name": "trace-feedback-score-2",
            "value": 0.5,
            "category_name": "trace-score-category-2",
            "reason": "trace-score-reason-2",
        },
    ]

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        feedback_scores=EXPECTED_TRACE_FEEDBACK_SCORES,
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        name="f_inner",
        feedback_scores=EXPECTED_INNER_SPAN_FEEDBACK_SCORES,
    )
