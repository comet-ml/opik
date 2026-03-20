"""
OpikInstrumentor — main user-facing class for AG2 integration.

Wraps AG2's built-in OTel instrumentor with an Opik-aware SpanProcessor,
so all AG2 spans are automatically converted to Opik traces and spans.

Usage::

    from opik.integrations.ag2 import OpikInstrumentor

    instrumentor = OpikInstrumentor(project_name="my-project")
    instrumentor.instrument_agent(agent)
    # LLM calls are automatically instrumented
"""

import logging
from typing import Any, Dict, List, Optional

from opentelemetry.sdk.trace import TracerProvider

from .span_processor import OpikSpanProcessor

LOGGER = logging.getLogger(__name__)


class OpikInstrumentor:
    """
    Instruments AG2 agents and LLM calls to produce Opik traces and spans.

    Creates an OTel TracerProvider with an OpikSpanProcessor and uses
    AG2's built-in ``autogen.opentelemetry`` instrumentors to generate spans
    that are bridged to Opik in real-time.

    Args:
        project_name: Opik project to log traces to.
        tags: Tags to attach to all traces.
        metadata: Extra metadata to attach to all traces and spans.
    """

    def __init__(
        self,
        project_name: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._processor = OpikSpanProcessor(
            project_name=project_name,
            tags=tags,
            metadata=metadata,
        )
        self._tracer_provider = TracerProvider()
        self._tracer_provider.add_span_processor(self._processor)

        # Instrument LLM calls globally
        try:
            from autogen.opentelemetry import instrument_llm_wrapper

            instrument_llm_wrapper(
                tracer_provider=self._tracer_provider,
                capture_messages=True,
            )
        except ImportError:
            LOGGER.warning(
                "Could not import autogen.opentelemetry.instrument_llm_wrapper. "
                "LLM calls will not be automatically instrumented. "
                "Make sure ag2 >= 0.11 is installed."
            )

    def instrument_agent(self, agent: Any) -> Any:
        """
        Instrument an AG2 agent to produce Opik spans for generate_reply,
        initiate_chat, tool execution, code execution, etc.

        Args:
            agent: An AG2 ``ConversableAgent`` (or subclass) instance.

        Returns:
            The same agent, instrumented.
        """
        try:
            from autogen.opentelemetry import instrument_agent

            instrument_agent(agent, tracer_provider=self._tracer_provider)
        except ImportError:
            LOGGER.warning(
                "Could not import autogen.opentelemetry.instrument_agent. "
                "Make sure ag2 >= 0.11 is installed."
            )
        return agent

    def instrument_pattern(self, pattern: Any) -> Any:
        """
        Instrument an AG2 group-chat pattern (e.g. ``AutoPattern``) to produce
        Opik spans for speaker selection and group chat orchestration.

        Args:
            pattern: An AG2 ``Pattern`` instance.

        Returns:
            The same pattern, instrumented.
        """
        try:
            from autogen.opentelemetry import instrument_pattern

            instrument_pattern(pattern, tracer_provider=self._tracer_provider)
        except ImportError:
            LOGGER.warning(
                "Could not import autogen.opentelemetry.instrument_pattern. "
                "Make sure ag2 >= 0.11 is installed."
            )
        return pattern

    def flush(self) -> None:
        """Flush all pending spans to Opik."""
        self._tracer_provider.force_flush()
        self._processor.force_flush()
