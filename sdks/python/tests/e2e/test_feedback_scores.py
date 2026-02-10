from typing import List
import opik
from opik import opik_context
from opik.types import BatchFeedbackScoreDict, FeedbackScoreDict
import pytest
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

    EXPECTED_TRACE_FEEDBACK_SCORES: List[BatchFeedbackScoreDict] = [
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

    EXPECTED_SPAN_FEEDBACK_SCORES: List[BatchFeedbackScoreDict] = [
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
        project_name=opik_client.project_name,
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        trace_id=span.trace_id,
        parent_span_id=None,
        name="span-name-1",
        feedback_scores=EXPECTED_SPAN_FEEDBACK_SCORES,
        project_name=opik_client.project_name,
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


@pytest.mark.xfail(
    reason="Backend bug (OPIK-3630): If incorrect project_name is specified, the project gets created and the score is not attached to the correct thread."
)
def test_log_threads_feedback_scores__project_name_fallback_logic(
    opik_client: opik.Opik,
):
    # Setup projects
    project_1 = opik_client.project_name + "-1"
    project_2 = opik_client.project_name + "-2"
    project_default = opik_client.project_name

    # Create threads in different projects
    thread_id_p1 = "thread-p1"
    thread_id_p2 = "thread-p2"
    thread_id_default = "thread-default"

    # Create traces with thread_ids to create threads
    opik_client.trace(name="trace-p1", project_name=project_1, thread_id=thread_id_p1)
    opik_client.trace(name="trace-p2", project_name=project_2, thread_id=thread_id_p2)
    opik_client.trace(name="trace-default", thread_id=thread_id_default)

    opik_client.flush()

    # Close threads before logging scores - otherwise backend will return 409 error
    opik_client.rest_client.traces.close_trace_thread(
        project_name=project_1, thread_id=thread_id_p1
    )
    opik_client.rest_client.traces.close_trace_thread(
        project_name=project_2, thread_id=thread_id_p2
    )
    opik_client.rest_client.traces.close_trace_thread(
        project_name=project_default, thread_id=thread_id_default
    )

    # Wait for threads to be closed
    threads_client = opik_client.get_threads_client()
    from opik import synchronization

    def check_threads_closed() -> bool:
        threads_p1 = threads_client.search_threads(
            project_name=project_1, filter_string=f'id = "{thread_id_p1}"'
        )
        threads_p2 = threads_client.search_threads(
            project_name=project_2, filter_string=f'id = "{thread_id_p2}"'
        )
        threads_default = threads_client.search_threads(
            project_name=project_default, filter_string=f'id = "{thread_id_default}"'
        )
        return (
            len(threads_p1) > 0
            and threads_p1[0].status == "closed"
            and len(threads_p2) > 0
            and threads_p2[0].status == "closed"
            and len(threads_default) > 0
            and threads_default[0].status == "closed"
        )

    synchronization.wait_for_done(lambda: check_threads_closed(), timeout=30)

    # Define scores with different project_name combinations
    scores: List[BatchFeedbackScoreDict] = [
        # 1. Per-score project_name (highest priority) - should go to project_1
        {
            "id": thread_id_p1,
            "name": "metric-p1",
            "value": 1.0,
            "project_name": project_1,
            "reason": "reason-p1",
        },
        # 2. Function parameter fallback - should go to project_2
        {
            "id": thread_id_p2,
            "name": "metric-p2",
            "value": 0.5,
            "reason": "reason-p2",
        },
        # 3. If no project_name is specified, the project gets created and
        # the score is not attached to the correct thread.
        {
            "id": thread_id_default,
            "name": "metric-default",
            "value": 0.0,
            "reason": "reason-default",
        },
    ]

    # Log scores with project_2 as parameter fallback
    opik_client.log_threads_feedback_scores(scores=scores, project_name=project_2)

    opik_client.flush()

    # Verifications
    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=thread_id_p1,
        project_name=project_1,
        feedback_scores=[
            {
                "id": thread_id_p1,
                "name": "metric-p1",
                "value": 1.0,
                "reason": "reason-p1",
                "category_name": None,
            },
        ],
    )

    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=thread_id_p2,
        project_name=project_2,
        feedback_scores=[
            {
                "id": thread_id_p2,
                "name": "metric-p2",
                "value": 0.5,
                "reason": "reason-p2",
                "category_name": None,
            },
        ],
    )

    verifiers.verify_thread(  # TODO: This fails (OPIK-3630)
        opik_client=opik_client,
        thread_id=thread_id_default,
        project_name=project_default,
        feedback_scores=[
            {
                "id": thread_id_default,
                "name": "metric-default",
                "value": 0.0,
                "reason": "reason-default",
                "category_name": None,
            },
        ],
    )
