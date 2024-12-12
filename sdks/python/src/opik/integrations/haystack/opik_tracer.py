import contextlib
import json
import os
from contextvars import ContextVar
from typing import Any, Dict, Iterator, List, Optional, Union

from haystack import logging
from haystack.components.generators.openai_utils import (
    _convert_message_to_openai_format,
)
from haystack.dataclasses import ChatMessage
from haystack.tracing import Span, Tracer
from haystack.tracing import tracer as proxy_tracer
from haystack.tracing import utils as tracing_utils

import opik

logger = logging.getLogger(__name__)

HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR = "HAYSTACK_OPIK_ENFORCE_FLUSH"
_SUPPORTED_GENERATORS = [
    "AzureOpenAIGenerator",
    "OpenAIGenerator",
    "AnthropicGenerator",
    "HuggingFaceAPIGenerator",
    "HuggingFaceLocalGenerator",
    "CohereGenerator",
]
_SUPPORTED_CHAT_GENERATORS = [
    "AzureOpenAIChatGenerator",
    "OpenAIChatGenerator",
    "AnthropicChatGenerator",
    "HuggingFaceAPIChatGenerator",
    "HuggingFaceLocalChatGenerator",
    "CohereChatGenerator",
]
_ALL_SUPPORTED_GENERATORS = _SUPPORTED_GENERATORS + _SUPPORTED_CHAT_GENERATORS


_PIPELINE_RUN_KEY = "haystack.pipeline.run"
_PIPELINE_INPUT_DATA_KEY = "haystack.pipeline.input_data"
_PIPELINE_OUTPUT_DATA_KEY = "haystack.pipeline.output_data"
_COMPONENT_NAME_KEY = "haystack.component.name"
_COMPONENT_TYPE_KEY = "haystack.component.type"
_COMPONENT_OUTPUT_KEY = "haystack.component.output"

# Context var used to keep track of tracing related info.
# This mainly useful for parents spans.
tracing_context_var: ContextVar[Dict[Any, Any]] = ContextVar("tracing_context")


class OpikSpan(Span):
    """
    Internal class representing a bridge between the Haystack span tracing API and Opik.
    """

    def __init__(self, span: Union[opik.Span, opik.Trace]) -> None:
        """
        Initialize a OpikSpan instance.

        Args:
            span: The span instance managed by Opik.
        """
        self._span = span
        # locally cache tags
        self._data: Dict[str, Any] = {}

    def set_tag(self, key: str, value: Any) -> None:
        """
        Set a generic tag for this span.

        Args:
            key: The tag key.
            value: The tag value.
        """
        coerced_value = tracing_utils.coerce_tag_value(value)
        self._span.update(metadata={key: coerced_value})
        self._data[key] = value

    def set_content_tag(self, key: str, value: Any) -> None:
        """
        Set a content-specific tag for this span.

        Args:
            key: The content tag key.
            value: The content tag value.
        """
        if not proxy_tracer.is_content_tracing_enabled:
            return
        if key.endswith(".input"):
            if "messages" in value:
                messages = [
                    _convert_message_to_openai_format(m) for m in value["messages"]
                ]
                self._span.update(input={"input": messages})
            else:
                self._span.update(input={"input": value})
        elif key.endswith(".output"):
            if "replies" in value:
                if all(isinstance(r, ChatMessage) for r in value["replies"]):
                    replies = [
                        _convert_message_to_openai_format(m) for m in value["replies"]
                    ]
                else:
                    replies = value["replies"]
                self._span.update(output={"replies": replies})
            else:
                self._span.update(output={"output": value})

        self._data[key] = value

    def raw_span(self) -> Union[opik.Span, opik.Trace]:
        """
        Return the underlying span instance.

        :return: The Opik span instance.
        """
        return self._span

    def get_correlation_data_for_logs(self) -> Dict[str, Any]:
        return {}


class OpikTracer(Tracer):
    """
    Internal class representing a bridge between the Haystack tracer and Opik.
    """

    def __init__(self, opik_client: opik.Opik, name: str = "Haystack") -> None:
        """
        Initialize a OpikTracer instance.

        Args:
            tracer: The Opik tracer instance.
            name: The name of the pipeline or component. This name will be used to identify the tracing run on the
                Opik dashboard.
        """
        if not proxy_tracer.is_content_tracing_enabled:
            logger.warning(
                "Traces will not be logged to Opik because Haystack tracing is disabled. "
                "To enable, set the HAYSTACK_CONTENT_TRACING_ENABLED environment variable to true "
                "before importing Haystack."
            )
        self._opik_client = opik_client
        self._context: List[opik.Span] = []
        self._name = name
        self.enforce_flush = (
            os.getenv(HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR, "true").lower() == "true"
        )

    @contextlib.contextmanager
    def trace(
        self,
        operation_name: str,
        tags: Optional[Dict[str, Any]] = None,
        parent_span: Optional[Span] = None,
    ) -> Iterator[Span]:
        tags = tags or {}
        span_name = tags.get(_COMPONENT_NAME_KEY, operation_name)

        # Create new span depending whether there's a parent span or not
        if not parent_span:
            if operation_name != _PIPELINE_RUN_KEY:
                logger.warning(
                    "Creating a new trace without a parent span is not recommended for operation '{operation_name}'.",
                    operation_name=operation_name,
                )
            # Create a new trace if no parent span is provided
            context = tracing_context_var.get({})

            trace = self._opik_client.trace(
                id=context.get("trace_id"),
                name=self._name,
                tags=context.get("tags"),
            )
            span = OpikSpan(trace)
        elif tags.get(_COMPONENT_TYPE_KEY) in _ALL_SUPPORTED_GENERATORS:
            span = OpikSpan(parent_span.raw_span().span(name=span_name, type="llm"))
        else:
            span = OpikSpan(parent_span.raw_span().span(name=span_name))

        self._context.append(span)
        span.set_tags(tags)

        yield span

        # Update span metadata based on component type
        if tags.get(_COMPONENT_TYPE_KEY) in _SUPPORTED_GENERATORS:
            # Haystack returns one meta dict for each message, but the 'usage' value
            # is always the same, let's just pick the first item
            meta = span._data.get(_COMPONENT_OUTPUT_KEY, {}).get("meta")
            if meta:
                m = meta[0]
                if isinstance(span._span, opik.Span):
                    span._span.update(
                        usage=m.get("usage") or None, model=m.get("model")
                    )
        elif tags.get(_COMPONENT_TYPE_KEY) in _SUPPORTED_CHAT_GENERATORS:
            replies = span._data.get(_COMPONENT_OUTPUT_KEY, {}).get("replies")
            if replies:
                meta = replies[0].meta
                if isinstance(span._span, opik.Span):
                    span._span.update(
                        usage=meta.get("usage") or None,
                        model=meta.get("model"),
                    )
        elif tags.get(_PIPELINE_INPUT_DATA_KEY) is not None:
            input_data = tags.get("haystack.pipeline.input_data", {})
            try:
                input_data = json.loads(input_data)
            except Exception:
                pass

            output_data = tags.get("haystack.pipeline.output_data", {})
            try:
                output_data = json.loads(output_data)
            except Exception:
                pass

            span._span.update(
                input=input_data,
                output=output_data,
            )

        raw_span = span.raw_span()
        if isinstance(raw_span, opik.Span):
            raw_span.end()
        self._context.pop()

        if self.enforce_flush:
            self.flush()

    def flush(self) -> None:
        self._opik_client.flush()

    def current_span(self) -> Optional[Span]:
        """
        Return the current active span.

        Returns:
            The current span if available, else None.
        """
        return self._context[-1] if self._context else None

    def get_project_url(self) -> str:
        """
        Return the URL to the tracing data.

        Returns:
            The URL to the project that includes the tracing data.
        """
        last_span = self.current_span()

        if last_span is None:
            return ""
        else:
            project_name = last_span.raw_span()._project_name
            return self._opik_client.get_project_url(project_name)

    def get_trace_id(self) -> Optional[str]:
        """
        Return the trace id of the current trace.

        Returns:
            The id of the current trace.
        """
        last_span = self.current_span()

        if last_span is None:
            return None
        else:
            return last_span.raw_span().trace_id
