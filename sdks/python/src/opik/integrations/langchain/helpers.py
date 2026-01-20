import logging
from typing import Dict, Any

from ... import _logging


LOGGER = logging.getLogger(__name__)


def extract_command_update(outputs: Dict[str, Any]) -> Dict[str, Any]:
    """Extract state updates from LangGraph Command objects.

    When a LangGraph node returns a Command, LangChain wraps it in {"output": Command(...)}.
    This function detects Command objects and extracts the update dict to properly log state changes.

    Args:
        outputs: The outputs dict from a LangChain Run.

    Returns:
        The extracted update dict if a Command is found, otherwise the original outputs.
    """
    if "output" in outputs and len(outputs) == 1:
        output_value = outputs["output"]
        # Duck-type check for Command object
        if hasattr(output_value, "update") and hasattr(output_value, "goto"):
            try:
                update_dict = output_value.update
                if isinstance(update_dict, dict):
                    _logging.log_once_at_level(
                        logging.DEBUG,
                        "Extracted state update from LangGraph Command object",
                        LOGGER,
                    )
                    return update_dict
            except Exception as e:
                LOGGER.warning(
                    f"Failed to extract update from Command-like object: {e}",
                    exc_info=True,
                )

    return outputs
