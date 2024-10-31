import logging
from typing import Any, List, Dict

LOGGER = logging.getLogger(__name__)


def aggregate(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Implementation is based on the following AWS example (see the section with converse_stream example).
    https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference-examples.html
    """

    result = {"output": {"message": {"role": "assistant", "content": {"text": ""}}}}
    for event in items:
        if "messageStart" in event:
            result["output"]["message"]["role"] = event["messageStart"]["role"]

        if "contentBlockDelta" in event:
            result["output"]["message"]["content"]["text"] += event[
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
