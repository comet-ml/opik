import logging
from typing import Any, List, Dict

LOGGER = logging.getLogger(__name__)


def aggregate_converse_stream_chunks(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Implementation is based on the following AWS example (see the section `Conversation with streaming example`).
    https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference-examples.html
    """

    result: Dict[str, Any] = {
        "output": {"message": {"role": "assistant", "content": [{"text": ""}]}}
    }

    for event in items:
        if "messageStart" in event:
            result["output"]["message"]["role"] = event["messageStart"]["role"]

        if "contentBlockDelta" in event:
            result["output"]["message"]["content"][0]["text"] += event[
                "contentBlockDelta"
            ]["delta"]["text"]

        if "messageStop" in event:
            result["stopReason"] = event["messageStop"]["stopReason"]

        if "metadata" in event:
            metadata = event["metadata"]
            if "usage" in metadata:
                result["usage"] = metadata["usage"]
            if "metrics" in event["metadata"]:
                result["metrics"] = {}
                result["metrics"]["latencyMs"] = metadata["metrics"]["latencyMs"]

    return result


def aggregate_invoke_agent_chunks(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    merged_chunks = b""

    for item in items:
        if "chunk" in item:
            merged_chunks += item["chunk"]["bytes"]

    result: Dict[str, Any] = {"output": merged_chunks.decode("utf-8")}

    return result
