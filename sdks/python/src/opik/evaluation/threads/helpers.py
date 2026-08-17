from typing import List, Callable, Optional

from . import evaluation_result
from ..metrics.conversation import types as conversation_types
from ...api_objects import opik_client
from ...api_objects.conversation import conversation_thread, conversation_factory
from ...rest_api import TraceThread, JsonListStringPublic, TracePublic
from ...types import BatchFeedbackScoreDict
from ...api_objects.threads import threads_client


def log_feedback_scores(
    results: List[evaluation_result.ThreadEvaluationResult],
    project_name: Optional[str],
    client: threads_client.ThreadsClient,
) -> None:
    for result in results:
        feedback_scores = [
            BatchFeedbackScoreDict(
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
    trace_context_transform: Optional[
        Callable[[TracePublic], Optional[List[str]]]
    ] = None,
) -> conversation_thread.ConversationThread:
    traces = client.search_traces(
        project_name=project_name,
        filter_string=f'thread_id = "{thread.id}"',
        max_results=max_results,
        truncate=False,
    )
    return conversation_factory.create_conversation_from_traces(
        traces=traces,
        input_transform=trace_input_transform,
        output_transform=trace_output_transform,
        context_transform=trace_context_transform,
    )


def strip_message_context(
    conversation: conversation_types.Conversation,
) -> conversation_types.Conversation:
    """Returns a copy of the conversation without the per-message `context` key.

    Metrics that don't declare `uses_message_context` must not see the context:
    conversation messages are rendered verbatim into judge prompts, so leaking it
    would silently change their prompts, scores and token usage.
    """
    return [
        {key: value for key, value in message.items() if key != "context"}  # type: ignore[misc]
        for message in conversation
    ]
