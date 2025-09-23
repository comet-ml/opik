import logging
from typing import Dict, Any, Tuple

from ... import _logging


LOGGER = logging.getLogger(__name__)
LANGGRAPH_OUTPUT_SIZE_THRESHOLD = 5000


def split_big_langgraph_outputs(
    outputs: Dict[str, Any],
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """
    Split large LangGraph outputs to extract messages for thread display.

    Returns:
        tuple: (filtered_output_for_display, additional_metadata_for_span)

    LangGraph agents often produce complex outputs with large internal state
    that breaks thread display. This extracts conversational messages for
    clean thread display while preserving the full state in metadata.
    """
    if not isinstance(outputs, dict):
        return outputs, {}

    langgraph_like_output = "messages" in outputs and len(outputs) > 1
    if langgraph_like_output:
        output_str = str(outputs)
        output_size = len(output_str)

        if output_size > LANGGRAPH_OUTPUT_SIZE_THRESHOLD:
            _logging.log_once_at_level(
                logging.WARNING,
                f"Filtering large LangGraph output ({output_size} chars) for thread display",
                LOGGER,
            )

            filtered_output = {
                "messages": outputs["messages"],
            }

            if "thread_id" in outputs:
                filtered_output["thread_id"] = outputs["thread_id"]

            additional_metadata = {
                "_opik_langgraph_full_output": outputs,
                "_opik_output_filtering": {
                    "filtered": True,
                    "original_size_chars": output_size,
                    "filtered_keys": [
                        k for k in outputs.keys() if k not in ["messages", "thread_id"]
                    ],
                    "reason": "Large LangGraph output filtered for better thread display",
                },
            }

            return filtered_output, additional_metadata

    return outputs, {}
