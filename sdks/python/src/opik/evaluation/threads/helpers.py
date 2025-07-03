from typing import List, Callable, Optional

from . import evaluation_result
from ...api_objects import opik_client
from ...api_objects.conversation import conversation_thread, conversation_factory
from ...rest_api import TraceThread, JsonListStringPublic
from ...types import FeedbackScoreDict
from ...api_objects.threads import threads_client


def log_feedback_scores(
    results: List[evaluation_result.ThreadEvaluationResult],
    project_name: Optional[str],
    client: threads_client.ThreadsClient,
) -> None:
    for result in results:
        feedback_scores = [
            FeedbackScoreDict(
                id=result.thread_id,
                name=score.name,
                value=score.value,
                reason=score.reason,
            )
            for score in result.scores
            if not score.scoring_failed
        ]
        client.log_threads_feedback_scores(
            scores=feedback_scores,
            project_name=project_name,
        )


def load_conversation_thread(
    thread: TraceThread,
    trace_input_transform: Callable[[JsonListStringPublic], str],
    trace_output_transform: Callable[[JsonListStringPublic], str],
    max_results: int,
    project_name: Optional[str],
    client: opik_client.Opik,
) -> conversation_thread.ConversationThread:
    traces = client.search_traces(
        project_name=project_name,
        filter_string=f'thread_id = "{thread.id}"',
        max_results=max_results,
    )
    return conversation_factory.create_conversation_from_traces(
        traces=traces,
        input_transform=trace_input_transform,
        output_transform=trace_output_transform,
    )
