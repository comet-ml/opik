import logging
from typing import Any, Dict, List, Optional

import pydantic


LOGGER = logging.getLogger(__name__)


class OpikArgsSpan(pydantic.BaseModel):
    """
    Configuration for span updates passed via opik_args parameter.
    """

    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None


class OpikArgsTrace(pydantic.BaseModel):
    """
    Configuration for trace updates passed via opik_args parameter.
    """

    thread_id: Optional[str] = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None


class OpikArgs(pydantic.BaseModel):
    """
    Configuration structure for special opik arguments passed to tracked functions.

    This allows users to specify span and trace updates without modifying function bodies.
    Tags and metadata are merged (not overwritten) with existing values.
    """

    span_args: Optional[OpikArgsSpan] = None
    trace_args: Optional[OpikArgsTrace] = None

    @classmethod
    def from_dict(cls, config_dict: Optional[Dict[str, Any]]) -> Optional["OpikArgs"]:
        """Create OpikArgs from dictionary, with validation."""
        if not isinstance(config_dict, dict):
            LOGGER.warning("opik_args must be a dictionary, got %s", type(config_dict))
            return None

        span_args = None
        trace_args = None

        if "span" in config_dict:
            span_data = config_dict["span"]
            if isinstance(span_data, dict):
                span_args = OpikArgsSpan(
                    tags=span_data.get("tags"),
                    metadata=span_data.get("metadata"),
                )
            else:
                LOGGER.warning("opik_args['span'] must be a dictionary")

        if "trace" in config_dict:
            trace_data = config_dict["trace"]
            if isinstance(trace_data, dict):
                trace_args = OpikArgsTrace(
                    thread_id=trace_data.get("thread_id"),
                    tags=trace_data.get("tags"),
                    metadata=trace_data.get("metadata"),
                )
            else:
                LOGGER.warning("opik_args['trace'] must be a dictionary")

        return cls(span_args=span_args, trace_args=trace_args)
