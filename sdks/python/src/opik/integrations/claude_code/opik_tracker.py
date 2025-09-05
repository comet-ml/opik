from typing import Optional, Callable, Any, AsyncGenerator
import logging

from opik.decorator.tracker import track
from opik.types import LLMProvider
import opik.opik_context as opik_context
from opik.api_objects import opik_client
from opik.decorator import tracing_runtime_config
import opik.context_storage as context_storage

LOGGER = logging.getLogger(__name__)


def track_claude_code(
    query_func: Callable,
    project_name: Optional[str] = None,
) -> Callable:
    """Adds Opik tracking to a Claude Code SDK query function.

    This function wraps the Claude Code SDK `query` function to automatically
    track all calls as Opik spans and traces. It creates:

    - A parent span for the entire query conversation
    - Child spans for each assistant response in the conversation
    - Child spans for system messages, user messages, and results
    - Captures input prompts and options
    - Tracks cost information from result messages
    - Provides clear visibility into conversation flow

    Args:
        query_func: The Claude Code SDK query function to track.
            Typically `claude_code_sdk.query`.
        project_name: The name of the project to log data to.
            If not provided, uses the default project.

    Returns:
        A wrapped version of the query function that automatically tracks calls.

    Example:
        ```python
        import opik
        from claude_code_sdk import query
        from opik.integrations.claude_code import track_claude_code

        # Configure Opik
        opik.configure()

        # Track the query function
        tracked_query = track_claude_code(query)

        # Use normally - tracking is automatic
        async for message in tracked_query("What is Python?"):
            if isinstance(message, AssistantMessage):
                for block in message.content:
                    if isinstance(block, TextBlock):
                        print(f"Claude: {block.text}")
        ```
    """

    @track(
        name="claude_code_query",
        project_name=project_name,
        tags=["claude_code", "conversation"],
        generations_aggregator=_process_generator_items,
    )
    async def wrapper(*args: Any, **kwargs: Any) -> AsyncGenerator[Any, None]:
        # Get the current span (created by @track decorator)
        current_span = opik_context.get_current_span_data()
        if current_span is None:
            # Fallback to just call the function if no span context
            async for message in query_func(*args, **kwargs):
                yield message
            return

        # Update the span with metadata
        metadata = {
            "provider": LLMProvider.ANTHROPIC,
            "function_name": query_func.__name__,
            "created_from": "claude_code",
        }

        current_span.update(metadata=metadata)

        # Initialize tracking variables
        assistant_message_count = 0
        system_message_count = 0
        user_message_count = 0
        other_message_count = 0

        try:
            # Iterate through the original async generator
            async for message in query_func(*args, **kwargs):
                # Create child spans for different message types
                if _is_assistant_message(message):
                    assistant_message_count += 1
                    _create_and_log_assistant_span(
                        current_span, message, assistant_message_count
                    )

                elif _is_system_message(message):
                    system_message_count += 1
                    _create_and_log_system_span(
                        current_span, message, system_message_count
                    )

                elif _is_user_message(message):
                    user_message_count += 1
                    _create_and_log_user_span(current_span, message, user_message_count)

                elif _is_result_message(message):
                    _create_and_log_result_span(current_span, message)

                else:
                    other_message_count += 1
                    _create_and_log_other_span(
                        current_span, message, other_message_count
                    )

                # Yield the message to maintain the original API
                yield message

        except Exception as e:
            LOGGER.error(f"Error in Claude Code query: {e}")
            current_span.update(output={"error": str(e)}, metadata={"error": True})
            raise

    return wrapper


def track_claude_code_with_options(
    query_func: Callable[..., Any],
    project_name: Optional[str] = None,
    **decorator_kwargs: Any,
) -> Callable[..., Any]:
    """Advanced Claude Code tracking with additional configuration options.

    This function provides more control over the tracking behavior with
    additional configuration options for the decorator.

    Args:
        query_func: The Claude Code SDK query function to track.
        project_name: The name of the project to log data to.
        **decorator_kwargs: Additional arguments passed to the decorator.

    Returns:
        A wrapped version of the query function with enhanced tracking.

    Example:
        ```python
        import opik
        from claude_code_sdk import query
        from opik.integrations.claude_code import track_claude_code_with_options

        # Configure Opik
        opik.configure()

        # Track with custom options
        tracked_query = track_claude_code_with_options(
            query,
            project_name="my_claude_project",
        )

        # Use normally
        async for message in tracked_query("Explain quantum computing"):
            # Handle messages...
            pass
        ```
    """
    # Use the same implementation but allow for future customization
    return track_claude_code(query_func, project_name=project_name)


def _extract_inputs(args: tuple, kwargs: dict) -> dict:
    """Extract and format inputs for span creation."""
    result: dict = {}

    # Extract prompt (first positional argument or from kwargs)
    if args:
        result["prompt"] = args[0]
    elif "prompt" in kwargs:
        result["prompt"] = kwargs["prompt"]

    # Extract options if present
    if len(args) > 1:
        options = args[1]
        if options is not None:
            result["options"] = _extract_options_dict(options)
    elif "options" in kwargs and kwargs["options"] is not None:
        result["options"] = _extract_options_dict(kwargs["options"])

    return result


def _extract_options_dict(options: Any) -> dict:
    """Extract options object as dictionary for logging."""
    try:
        options_dict: dict = {}
        if hasattr(options, "system_prompt") and options.system_prompt:
            options_dict["system_prompt"] = options.system_prompt
        if hasattr(options, "allowed_tools") and options.allowed_tools:
            options_dict["allowed_tools"] = list(options.allowed_tools)
        if hasattr(options, "max_turns") and options.max_turns is not None:
            options_dict["max_turns"] = options.max_turns
        if hasattr(options, "add_dirs") and options.add_dirs:
            options_dict["add_dirs"] = list(options.add_dirs)
        if (
            hasattr(options, "continue_conversation")
            and options.continue_conversation is not None
        ):
            options_dict["continue_conversation"] = options.continue_conversation
        if hasattr(options, "disallowed_tools") and options.disallowed_tools:
            options_dict["disallowed_tools"] = list(options.disallowed_tools)
        if hasattr(options, "extra_args") and options.extra_args:
            options_dict["extra_args"] = list(options.extra_args)
        if (
            hasattr(options, "max_thinking_tokens")
            and options.max_thinking_tokens is not None
        ):
            options_dict["max_thinking_tokens"] = options.max_thinking_tokens
        if hasattr(options, "mcp_servers") and options.mcp_servers:
            options_dict["mcp_servers"] = list(options.mcp_servers)
        return options_dict
    except Exception as e:
        LOGGER.warning(f"Failed to extract options: {e}")
        return {"options": str(options)}


def _is_assistant_message(message: Any) -> bool:
    """Check if message is an AssistantMessage."""
    return hasattr(message, "content") and hasattr(message.content, "__iter__")


def _is_system_message(message: Any) -> bool:
    """Check if message is a SystemMessage."""
    return type(message).__name__ == "SystemMessage"


def _is_user_message(message: Any) -> bool:
    """Check if message is a UserMessage."""
    return type(message).__name__ == "UserMessage"


def _is_result_message(message: Any) -> bool:
    """Check if message is a ResultMessage."""
    return hasattr(message, "total_cost_usd")


def _create_and_log_assistant_span(
    parent_span_data: Any, message: Any, count: int
) -> None:
    """Create and log a child span for an AssistantMessage."""
    # Convert dataclass to dict for proper serialization
    message_dict: dict = _dataclass_to_dict(message)

    # Extract text content for readability (but don't filter based on it)
    text_content: list = []
    try:
        if hasattr(message, "content"):
            for block in message.content:
                if hasattr(block, "text") and block.text.strip():
                    text_content.append(block.text.strip())
    except Exception as e:
        LOGGER.debug(f"Could not extract text content from assistant message: {e}")

    # Create child span data
    child_span_data = parent_span_data.create_child_span_data(
        name=f"assistant_response_{count}",
        type="llm",
        input={"message_type": "assistant"},
        output={
            "message_data": message_dict,
            "text_content": text_content if text_content else None,
        },
        metadata={
            "provider": LLMProvider.ANTHROPIC,
            "message_type": "AssistantMessage",
            "response_number": count,
            "has_text_content": bool(text_content),
        },
        tags=["claude_code", "assistant_response"],
    )

    _log_child_span(child_span_data)


def _create_and_log_system_span(
    parent_span_data: Any, message: Any, count: int
) -> None:
    """Create and log a child span for a SystemMessage."""
    # Convert dataclass to dict for proper serialization
    message_dict: dict = _dataclass_to_dict(message)

    child_span_data = parent_span_data.create_child_span_data(
        name=f"system_message_{count}",
        type="general",
        input={"message_type": "system"},
        output={"message_data": message_dict},
        metadata={
            "provider": LLMProvider.ANTHROPIC,
            "message_type": "SystemMessage",
            "message_number": count,
        },
        tags=["claude_code", "system_message"],
    )

    _log_child_span(child_span_data)


def _create_and_log_user_span(parent_span_data: Any, message: Any, count: int) -> None:
    """Create and log a child span for a UserMessage."""
    # Convert dataclass to dict for proper serialization
    message_dict: dict = _dataclass_to_dict(message)

    child_span_data = parent_span_data.create_child_span_data(
        name=f"user_message_{count}",
        type="general",
        input={"message_type": "user"},
        output={"message_data": message_dict},
        metadata={
            "provider": LLMProvider.ANTHROPIC,
            "message_type": "UserMessage",
            "message_number": count,
        },
        tags=["claude_code", "user_message"],
    )

    _log_child_span(child_span_data)


def _create_and_log_result_span(parent_span_data: Any, message: Any) -> None:
    """Create and log a child span for a ResultMessage."""
    # Convert dataclass to dict for proper serialization
    message_dict: dict = _dataclass_to_dict(message)

    child_span_data = parent_span_data.create_child_span_data(
        name="conversation_result",
        type="general",
        input={"message_type": "result"},
        output={"message_data": message_dict},
        metadata={
            "provider": LLMProvider.ANTHROPIC,
            "message_type": "ResultMessage",
        },
        tags=["claude_code", "result_message"],
    )

    _log_child_span(child_span_data)


def _create_and_log_other_span(parent_span_data: Any, message: Any, count: int) -> None:
    """Create and log a child span for other message types."""
    message_type: str = type(message).__name__
    # Convert dataclass to dict for proper serialization
    message_dict: dict = _dataclass_to_dict(message)

    child_span_data = parent_span_data.create_child_span_data(
        name=f"{message_type.lower()}_{count}",
        type="general",
        input={"message_type": message_type.lower()},
        output={"message_data": message_dict},
        metadata={
            "provider": LLMProvider.ANTHROPIC,
            "message_type": message_type,
            "message_number": count,
        },
        tags=["claude_code", "other_message"],
    )

    _log_child_span(child_span_data)


def _dataclass_to_dict(obj: Any) -> dict:
    """Convert a dataclass to a dictionary for proper serialization."""
    try:
        # Try to use asdict if it's a dataclass
        from dataclasses import asdict

        return asdict(obj)
    except (TypeError, AttributeError):
        # Fallback to converting to dict manually
        try:
            return {
                field: getattr(obj, field) for field in obj.__dataclass_fields__.keys()
            }
        except AttributeError:
            # If not a dataclass, convert to string as fallback
            return {"content": str(obj)}


def _log_child_span(child_span_data: Any) -> None:
    """Helper function to log a child span."""
    # Set end time to mark span as completed
    import opik.datetime_helpers as datetime_helpers

    child_span_data.end_time = datetime_helpers.local_timestamp()

    # Add to context and log the span
    context_storage.add_span_data(child_span_data)

    # Log the complete span
    client = opik_client.get_client_cached()
    if tracing_runtime_config.is_tracing_active():
        client.span(**child_span_data.as_parameters)


def _finalize_parent_span(
    parent_span: Any, messages: list, total_cost: float, counts: dict
) -> None:
    """Update the parent span with final conversation summary."""

    # Create output summary
    output: dict = {
        "conversation_summary": {
            "total_messages": len(messages),
            **counts,
        },
        "total_cost_usd": total_cost,
    }

    # Add final assistant response if available
    last_assistant_response: Optional[str] = None
    for msg in reversed(messages):
        if _is_assistant_message(msg):
            try:
                text_parts: list = []
                for block in msg.content:
                    if hasattr(block, "text"):
                        text_parts.append(block.text)
                if text_parts:
                    last_assistant_response = " ".join(text_parts)
                    break
            except Exception:
                pass

    if last_assistant_response:
        output["final_response"] = last_assistant_response

    # Update parent span
    parent_span.update(
        output=output,
        metadata={
            "provider": LLMProvider.ANTHROPIC,
            "total_cost_usd": total_cost,
            "message_count": len(messages),
            **counts,
        },
    )


def _process_generator_items(generator_items: list) -> dict:
    """Process the collected generator items and structure them for readability."""
    all_messages: list = []
    conversation_total_cost: float = 0.0

    for item in generator_items:
        try:
            # Convert all messages to dict format
            message_dict = _dataclass_to_dict(item)
            message_type = type(item).__name__

            # Extract additional info based on message type
            message_info: dict = {
                "type": message_type.lower(),
                "message_data": message_dict,
            }

            # Add type-specific information
            if hasattr(item, "content") and hasattr(item.content, "__iter__"):
                # AssistantMessage - extract text content for readability
                text_content: list = []
                for block in item.content:
                    if hasattr(block, "text") and block.text.strip():
                        text_content.append(block.text.strip())
                if text_content:
                    message_info["text_content"] = text_content
                    message_info["readable_content"] = " ".join(text_content)
            elif hasattr(item, "total_cost_usd"):
                # ResultMessage
                cost = getattr(item, "total_cost_usd", 0.0)
                if isinstance(cost, (int, float)):
                    conversation_total_cost = float(cost)
                    message_info["cost_usd"] = float(cost)

            all_messages.append(message_info)

        except Exception as e:
            LOGGER.warning(f"Failed to process generator item {type(item)}: {e}")
            all_messages.append(
                {
                    "type": "error",
                    "message_data": {"content": str(item)},
                    "error": str(e),
                }
            )

    return {
        "conversation_flow": all_messages,
        "result_info": {
            "total_cost_usd": conversation_total_cost,
            "total_messages_count": len(all_messages),
        },
    }
