import time
import uuid
import pytest

from opik import synchronization
from opik.api_objects.threads import threads_client
from opik.evaluation import metrics
from opik.evaluation.threads import evaluator
from opik.types import FeedbackScoreDict

from ...testlib import environment
from .. import verifiers


@pytest.fixture
def real_model_conversation():
    return [
        {
            "role": "user",
            "content": "I need to book a flight to New York and find a hotel.",
        },
        {
            "role": "assistant",
            "content": "I can help you with that. For flights to New York, what dates are you looking to travel?",
        },
        {"role": "user", "content": "Next weekend, from Friday to Sunday."},
        {
            "role": "assistant",
            "content": "Great! I recommend checking airlines like Delta, United, or JetBlue for flights to New York next weekend. For hotels, what's your budget range and preferred location in New York?",
        },
        {"role": "user", "content": "Around $200 per night, preferably in Manhattan."},
        {
            "role": "assistant",
            "content": "For Manhattan hotels around $200/night, you might want to look at options like Hotel Beacon, Pod 51, or CitizenM Times Square. These are well-rated options in that price range. Would you like more specific recommendations for any of these?",
        },
    ]


@pytest.fixture
def active_thread_and_project_name(
    opik_client, real_model_conversation, temporary_project_name
):
    thread_id = str(uuid.uuid4())[-6:]

    # create conversation traces
    i = 0
    while i < len(real_model_conversation) - 1:
        opik_client.trace(
            name=f"trace-name-{i}:{thread_id}",
            input={"input": f"{real_model_conversation[i]['content']}"},
            output={"output": f"{real_model_conversation[i + 1]['content']}"},
            project_name=temporary_project_name,
            thread_id=thread_id,
        )
        time.sleep(0.1)
        i += 2

    opik_client.flush()

    return thread_id, temporary_project_name


def _all_threads_closed(
    project_name: str, threads_client_: threads_client.ThreadsClient
) -> bool:
    threads = threads_client_.search_threads(
        project_name=project_name, filter_string='status = "active"'
    )
    return len(threads) == 0


def _one_thread_is_active(
    project_name: str, threads_client_: threads_client.ThreadsClient
) -> bool:
    threads = threads_client_.search_threads(
        project_name=project_name, filter_string='status = "active"'
    )
    return len(threads) == 1


@pytest.fixture
def eval_project_name(temporary_project_name: str) -> str:
    return temporary_project_name


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluate_threads__happy_path(
    opik_client, active_thread_and_project_name, eval_project_name
):
    active_thread, project_name = active_thread_and_project_name
    threads_client_ = opik_client.get_threads_client()
    # wait for active threads to propagate
    if not synchronization.until(
        lambda: _one_thread_is_active(project_name, threads_client_), max_try_seconds=30
    ):
        raise AssertionError(f"Failed to create threads in project '{project_name}'")

    # close threads before evaluating - otherwise backend will return 409 error on logging scores
    opik_client.rest_client.traces.close_trace_thread(
        project_name=project_name, thread_id=active_thread
    )

    # wait for closed threads to propagate
    if not synchronization.until(
        lambda: _all_threads_closed(project_name, threads_client_), max_try_seconds=30
    ):
        raise AssertionError(
            f"Failed to get closed threads from project '{project_name}'"
        )

    metrics_ = [
        metrics.ConversationalCoherenceMetric(window_size=2),
        metrics.UserFrustrationMetric(window_size=2),
        metrics.SessionCompletenessQuality(),
    ]

    result = evaluator.evaluate_threads(
        project_name=project_name,
        filter_string=f'id = "{active_thread}"',
        metrics=metrics_,
        eval_project_name=eval_project_name,
        trace_input_transform=lambda x: x["input"],
        trace_output_transform=lambda x: x["output"],
        verbose=1,
    )

    assert result is not None
    assert len(result.results) == 1  # we have only one thread

    thread_result = result.results[0]
    assert thread_result.thread_id == active_thread
    assert len(thread_result.scores) == len(metrics_)

    feedback_scores = [
        FeedbackScoreDict(
            id=active_thread,
            name=score.name,
            value=score.value,
            reason=score.reason.strip(),
            category_name=None,
        )
        for score in thread_result.scores
        if not score.scoring_failed
    ]

    verifiers.verify_thread(
        opik_client=opik_client,
        thread_id=active_thread,
        project_name=project_name,
        feedback_scores=feedback_scores,
    )
