from typing import List, Callable

from opik.rest_api import TracePublic, JsonListStringPublic
from . import conversation


def create_conversation_from_traces(
    traces: List[TracePublic],
    input_transform: Callable[[JsonListStringPublic], str],
    output_transform: Callable[[JsonListStringPublic], str],
) -> conversation.Conversation:
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
    discussion = conversation.Conversation()
    for trace in traces:
        trace_input = input_transform(trace.input)
        discussion.add_user_message(trace_input)

        trace_output = output_transform(trace.output)
        discussion.add_assistant_message(trace_output)

    return discussion
