import json
import logging
from typing import Any, Dict, List

LOGGER = logging.getLogger(__name__)


def _handle_message_start(event: Dict[str, Any], result: Dict[str, Any]) -> None:
    """Extract role from messageStart event."""
    message_start = event.get("messageStart")
    if isinstance(message_start, dict):
        role = message_start.get("role")
        if role:
            result["output"]["message"]["role"] = role


def _block_index(block_event: Dict[str, Any], default: int) -> int:
    """
    contentBlockIndex is required by the API; `default` only covers events
    without it. int() makes a malformed index fail this event, not the block
    ordering after the loop.
    """
    return int(block_event.get("contentBlockIndex", default))


def _parse_tool_input(tool_input: str, index: int) -> Any:
    """Parse the streamed input into the JSON value non-streaming Converse returns."""
    if not tool_input:
        return {}  # Claude streams a no-argument call as input ""
    try:
        return json.loads(tool_input)
    except ValueError as e:
        # e.g. cut off by maxTokens: keep what was streamed
        LOGGER.debug("Could not parse tool input of content block %s: %s", index, e)
        return tool_input


def _handle_content_block_start(
    event: Dict[str, Any], blocks: Dict[int, Dict[str, Any]]
) -> None:
    """Extract toolUseId and name from contentBlockStart event."""
    content_block_start = event.get("contentBlockStart")
    if not isinstance(content_block_start, dict):
        return

    start = content_block_start.get("start")
    if isinstance(start, dict) and isinstance(start.get("toolUse"), dict):
        # Without an index, a start opens the next block.
        index = _block_index(content_block_start, max(blocks, default=-1) + 1)
        blocks.setdefault(index, {}).setdefault("toolUse", {}).update(start["toolUse"])


def _handle_content_block_delta(
    event: Dict[str, Any], blocks: Dict[int, Dict[str, Any]]
) -> None:
    """
    Extract content from contentBlockDelta event.

    Handles multiple delta types:
    - delta.text: Regular text streaming
    - delta.toolUse: Structured output / tool calls (Issue #3829)
    """
    content_block_delta = event.get("contentBlockDelta")
    if not isinstance(content_block_delta, dict):
        return

    delta = content_block_delta.get("delta")
    if not isinstance(delta, dict):
        return

    # Without an index, a delta belongs to the latest block.
    index = _block_index(content_block_delta, max(blocks, default=0))

    # Handle regular text streaming
    if "text" in delta:
        block = blocks.setdefault(index, {})
        block["text"] = block.get("text", "") + delta["text"]
        return

    # Handle structured output / tool use (Issue #3829)
    # Ref: https://github.com/comet-ml/opik/issues/3829
    # The tool input arrives as JSON string fragments, one per delta.
    if "toolUse" in delta:
        tool_use = blocks.setdefault(index, {}).setdefault("toolUse", {})
        fragment = delta["toolUse"].get("input", "")
        tool_use["input"] = tool_use.get("input", "") + fragment
        return

    # Log other delta types for future compatibility
    LOGGER.debug("Unknown delta type in contentBlockDelta: %s", list(delta.keys()))


def _handle_message_stop(event: Dict[str, Any], result: Dict[str, Any]) -> None:
    """Extract stopReason from messageStop event."""
    message_stop = event.get("messageStop")
    if isinstance(message_stop, dict):
        stop_reason = message_stop.get("stopReason")
        if stop_reason:
            result["stopReason"] = stop_reason


def _handle_metadata(event: Dict[str, Any], result: Dict[str, Any]) -> None:
    """Extract usage and metrics from metadata event."""
    metadata = event.get("metadata")
    if not isinstance(metadata, dict):
        return

    # Extract usage information
    if "usage" in metadata:
        result["usage"] = metadata["usage"]

    # Extract metrics information
    if "metrics" in metadata:
        metrics = metadata["metrics"]
        if isinstance(metrics, dict) and "latencyMs" in metrics:
            result["metrics"] = {"latencyMs": metrics["latencyMs"]}


def aggregate_converse_stream_chunks(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Aggregate streaming chunks from AWS Bedrock converse_stream API into a single response.

    This function handles various event structures from different Bedrock model providers:
    - Anthropic (Claude): Standard messageStart, contentBlockDelta, messageStop events
    - Amazon (Nova): Amazon's proprietary event format with potential variations
    - Meta (Llama): Open-source model events with different tokenization patterns
    - Mistral (Pixtral): Multimodal model events that may include additional content types
    - DeepSeek (R1): Reasoning model events with extended thought processes (OPIK-2910 fix)

    Event Structure Variations by Provider:
    ========================================

    Standard Converse Stream Events (from AWS documentation):
    - messageStart: Contains role and initial metadata
    - contentBlockStart: Marks beginning of content block
    - contentBlockDelta: Contains incremental text in delta.text
    - contentBlockStop: Marks end of content block
    - messageStop: Contains stopReason (e.g., "end_turn", "stop_sequence")
    - metadata: Contains usage stats and performance metrics

    Known Variations:
    - DeepSeek R1: May have different delta structure or additional reasoning fields (OPIK-2910)
    - Tool Use/Structured Output: delta.toolUse instead of delta.text (Issue #3829)
    - Multimodal models: May include non-text content blocks (images, documents)
    - Different models: Varying field names, nesting levels, or optional fields

    References:
    - AWS Bedrock Converse API: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html
    - Streaming Events: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStreamOutput.html
    - ContentBlockDelta: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ContentBlockDelta.html
    - Tool Use Guide: https://docs.aws.amazon.com/bedrock/latest/userguide/tool-use.html
    - DeepSeek R1 Issue: https://comet-ml.atlassian.net/browse/OPIK-2910
    - Tool Use Issue: https://github.com/comet-ml/opik/issues/3829

    Args:
        items: List of streaming event dictionaries from Bedrock converse_stream

    Returns:
        Aggregated response dictionary with structure:
        {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [{"text": "aggregated text"}]
                }
            },
            "stopReason": "end_turn",  # Optional
            "usage": {...},  # Optional
            "metrics": {"latencyMs": ...}  # Optional
        }
    """

    result: Dict[str, Any] = {
        "output": {"message": {"role": "assistant", "content": [{"text": ""}]}}
    }
    blocks: Dict[int, Dict[str, Any]] = {}

    for event in items:
        if not isinstance(event, dict):
            LOGGER.debug("Skipping non-dictionary event: %s", type(event))
            continue

        try:
            if "messageStart" in event:
                _handle_message_start(event, result)

            if "contentBlockStart" in event:
                _handle_content_block_start(event, blocks)

            if "contentBlockDelta" in event:
                _handle_content_block_delta(event, blocks)

            if "messageStop" in event:
                _handle_message_stop(event, result)

            if "metadata" in event:
                _handle_metadata(event, result)

        except Exception as e:
            LOGGER.warning(
                "Unexpected error processing event: %s. Event: %s",
                str(e),
                event,
                exc_info=True,
            )

    if blocks:
        # One entry per content block, in contentBlockIndex order, with the tool
        # input parsed: the layout of the non-streaming Converse response.
        for index, block in blocks.items():
            if "toolUse" in block:
                tool_use = block["toolUse"]
                tool_use["input"] = _parse_tool_input(tool_use.get("input", ""), index)
        result["output"]["message"]["content"] = [blocks[i] for i in sorted(blocks)]

    return result


def aggregate_invoke_agent_chunks(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Aggregate streaming chunks from AWS Bedrock invoke_agent API.

    Note: The implementation uses a simplified approach as the completion payload
    only contains chunks without additional metadata (as of implementation date).

    Reference:
    - https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/bedrock-agent-runtime/client/invoke_agent.html

    Args:
        items: List of chunk event dictionaries from invoke_agent stream

    Returns:
        Aggregated response dictionary with decoded text output
    """
    merged_chunks = b""

    for item in items:
        if isinstance(item, dict) and "chunk" in item:
            chunk = item["chunk"]
            if isinstance(chunk, dict) and "bytes" in chunk:
                merged_chunks += chunk["bytes"]

    return {"output": merged_chunks.decode("utf-8")}
