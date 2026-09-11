import logging
from typing import List, Callable, Optional

from opik.rest_api import TracePublic, JsonListStringPublic
from . import conversation_thread

LOGGER = logging.getLogger(__name__)


def create_conversation_from_traces(
    traces: List[TracePublic],
    input_transform: Callable[[JsonListStringPublic], str],
    output_transform: Callable[[JsonListStringPublic], str],
    context_transform: Optional[Callable[[TracePublic], Optional[List[str]]]] = None,
) -> conversation_thread.ConversationThread:
    """
    Creates a conversation object from given traces, transforming inputs and outputs using
    provided transformation functions. The method processes each trace to compose a complete
    conversation by consecutively adding user messages and assistant messages.

    Args:
        traces: A list of TracePublic objects representing trace data for user
            and assistant interaction flows.
        input_transform: A callable function that transforms the input data
            from a JsonListStringPublic format to a string.
        output_transform: A callable function that transforms the output data
            from a JsonListStringPublic format to a string.
        context_transform: An optional callable that receives the whole trace and
            returns the context the assistant message was grounded on, e.g. the
            documents retrieved by a RAG pipeline for that turn. The context is
            attached to the assistant message produced by the same trace.

    Returns:
        A Conversation object that contains user and assistant message
        sequences derived from the provided traces.
    """
    # Sort traces by start time to ensure they are processed in the correct order -
    # the first user message should be first recorded
    traces.sort(key=lambda trace_: trace_.start_time)

    discussion = conversation_thread.ConversationThread()
    for trace in traces:
        trace_input = input_transform(trace.input)
        if trace_input is not None:
            discussion.add_user_message(trace_input)

        trace_context = context_transform(trace) if context_transform else None

        trace_output = output_transform(trace.output)
        if trace_output is not None:
            discussion.add_assistant_message(trace_output, context=trace_context)
        elif trace_context is not None:
            LOGGER.debug(
                "Trace '%s' has context but no assistant message, context is ignored.",
                trace.id,
            )

    return discussion
