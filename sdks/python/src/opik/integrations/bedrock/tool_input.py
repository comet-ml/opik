"""Helpers shared by the Bedrock stream aggregators."""

import json
import logging
from typing import Any


def parse_streamed_tool_input(
    tool_input: str, index: int, logger: logging.Logger
) -> Any:
    """Parse a streamed tool input into the JSON value the non-streamed response has.

    ``logger`` is the calling aggregator's, so a parse failure is reported
    under the API it came from.
    """
    if not tool_input:
        return {}  # Claude streams a no-argument call as input ""
    try:
        return json.loads(tool_input)
    except ValueError as e:
        # e.g. cut off by max_tokens: keep what was streamed
        logger.debug("Could not parse tool input of content block %s: %s", index, e)
        return tool_input
