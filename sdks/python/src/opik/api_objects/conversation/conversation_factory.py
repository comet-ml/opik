from typing import List, Callable

from opik.rest_api import TracePublic, JsonListStringPublic
from . import conversation_thread


def create_conversation_from_traces(
    traces: List[TracePublic],
    input_transform: Callable[[JsonListStringPublic], str],
    output_transform: Callable[[JsonListStringPublic], str],
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

        trace_output = output_transform(trace.output)
        if trace_output is not None:
            discussion.add_assistant_message(trace_output)

    return discussion
