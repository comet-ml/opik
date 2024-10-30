import logging
from typing import Any, List, Dict

LOGGER = logging.getLogger(__name__)


def aggregate(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Implementation is based on the following AWS example.
    https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference-examples.html
    """
    output = {"output": {"message": {"content": {"text": ""}}}}
    for event in items:
        if "messageStart" in event:
            output["output"]["message"]["role"] = event["messageStart"]["role"]

        if "contentBlockDelta" in event:
            output["output"]["message"]["content"]["text"] += event[
                "contentBlockDelta"
            ]["delta"]["text"]

        if "messageStop" in event:
            output["stopReason"] = event["messageStop"]["stopReason"]

        if "metadata" in event:
            metadata = event["metadata"]
            if "usage" in metadata:
                output["usage"] = metadata["usage"]
            if "metrics" in event["metadata"]:
                output["metrics"] = {}
                output["metrics"]["latencyMs"] = metadata["metrics"]["latencyMs"]

    return output
