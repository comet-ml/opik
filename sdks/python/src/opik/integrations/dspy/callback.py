from typing import Any, Dict, Optional, Union
import logging

import dspy
from dspy.utils import callback as dspy_callback

from opik import types, opik_context, context_storage
from opik.api_objects import helpers, span, trace, opik_client
from opik.decorator import error_info_collector

from .graph import build_mermaid_graph_from_module

LOGGER = logging.getLogger(__name__)

SpanOrTraceData = Union[span.SpanData, trace.TraceData]


class OpikCallback(dspy_callback.BaseCallback):
    """
    Callback for DSPy Opik logging.

    Args:
        project_name: The name of the Opik project to log data.
        log_graph: If True, will log a mermaid diagram for each
            module
    """

    def __init__(
        self,
        project_name: Optional[str] = None,
        log_graph: bool = False,
    ):
        self._map_call_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_call_id_to_trace_data: Dict[str, trace.TraceData] = {}

        self._origins_metadata: Dict[str, Any] = {"created_from": "dspy"}

        self._context_storage = context_storage.OpikContextStorage()

        self._project_name = project_name
        self.log_graph = log_graph

        self._opik_client = opik_client.get_client_cached()

    def on_module_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        # First we check the callback's context
        if (current_span_data := self._context_storage.top_span_data()) is not None:
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_span_data,
                instance=instance,
                inputs=inputs,
            )
        elif (current_trace_data := self._context_storage.get_trace_data()) is not None:
            self._attach_span_to_existing_trace(
                call_id=call_id,
                current_trace_data=current_trace_data,
                instance=instance,
                inputs=inputs,
            )
        # Callback's context is empty, we check opik's context
        elif (current_span_data := opik_context.get_current_span_data()) is not None:
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_span_data,
                instance=instance,
                inputs=inputs,
            )
        elif (current_trace_data := opik_context.get_current_trace_data()) is not None:
            self._attach_span_to_existing_trace(
                call_id=call_id,
                current_trace_data=current_trace_data,
                instance=instance,
                inputs=inputs,
            )
        else:
            # Both callback's and opik's context are empty
            self._start_trace(
                call_id=call_id,
                instance=instance,
                inputs=inputs,
            )

    def _attach_span_to_existing_span(
        self,
        call_id: str,
        current_span_data: span.SpanData,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        project_name = helpers.resolve_child_span_project_name(
            parent_project_name=current_span_data.project_name,
            child_project_name=self._project_name,
        )
        span_type = self._get_span_type(instance)

        span_data = span.SpanData(
            trace_id=current_span_data.trace_id,
            parent_span_id=current_span_data.id,
            name=instance.__class__.__name__,
            input=inputs,
            type=span_type,
            project_name=project_name,
            metadata=self._get_opik_metadata(instance),
        )
        self._start_span(call_id=call_id, span_data=span_data)

    def _attach_span_to_existing_trace(
        self,
        call_id: str,
        current_trace_data: trace.TraceData,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        project_name = helpers.resolve_child_span_project_name(
            current_trace_data.project_name,
            self._project_name,
        )
        span_type = self._get_span_type(instance)

        span_data = span.SpanData(
            trace_id=current_trace_data.id,
            parent_span_id=None,
            name=instance.__class__.__name__,
            input=inputs,
            type=span_type,
            project_name=project_name,
            metadata=self._get_opik_metadata(instance),
        )
        self._start_span(call_id=call_id, span_data=span_data)

    def _start_span(self, call_id: str, span_data: span.SpanData) -> None:
        self._map_call_id_to_span_data[call_id] = span_data
        self._set_current_context_data(span_data)

        if self._opik_client.config.log_start_trace_span:
            self._opik_client.span(**span_data.as_start_parameters)

    def _start_trace(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        trace_data = trace.TraceData(
            name=instance.__class__.__name__,
            input=inputs,
            metadata=self._get_opik_metadata(instance),
            project_name=self._project_name,
        )
        self._map_call_id_to_trace_data[call_id] = trace_data
        self._set_current_context_data(trace_data)

        if self._opik_client.config.log_start_trace_span:
            self._opik_client.trace(**trace_data.as_start_parameters)

    def on_module_end(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
    ) -> None:
        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
        )
        self._end_trace(call_id=call_id)

    def _end_trace(self, call_id: str) -> None:
        if trace_data := self._map_call_id_to_trace_data.pop(call_id, None):
            trace_data.init_end_time()
            self._opik_client.trace(**trace_data.as_parameters)

            if self._context_storage.get_trace_data() == trace_data:
                self._context_storage.set_trace_data(None)

    def _end_span(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
    ) -> None:
        if span_data := self._map_call_id_to_span_data.pop(call_id, None):
            if exception:
                error_info = error_info_collector.collect(exception)
                span_data.update(error_info=error_info)

            span_data.update(output={"output": outputs}).init_end_time()
            self._opik_client.span(**span_data.as_parameters)

            # remove span data from context
            current_span = self._context_storage.top_span_data()
            if current_span and current_span.id == span_data.id:
                self._context_storage.pop_span_data()

    def _collect_common_span_data(
        self, instance: Any, inputs: Dict[str, Any]
    ) -> span.SpanData:
        current_callback_context_data = self._get_current_context_data()
        assert current_callback_context_data is not None

        project_name = helpers.resolve_child_span_project_name(
            current_callback_context_data.project_name,
            self._project_name,
        )

        if isinstance(current_callback_context_data, span.SpanData):
            trace_id = current_callback_context_data.trace_id
            parent_span_id = current_callback_context_data.id
        else:
            trace_id = current_callback_context_data.id
            parent_span_id = None

        span_type = self._get_span_type(instance)

        return span.SpanData(
            trace_id=trace_id,
            parent_span_id=parent_span_id,
            name=(
                instance.name
                if hasattr(instance, "name")
                else instance.__class__.__name__
            ),
            input=inputs,
            type=span_type,
            project_name=project_name,
            metadata=self._get_opik_metadata(instance),
        )

    def on_lm_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        span_data = self._collect_common_span_data(instance, inputs)

        provider, model = instance.model.split(r"/", 1)

        span_data.update(
            provider=provider,
            model=model,
            name=f"{span_data.name}: {provider} - {model}",
        )
        self._map_call_id_to_span_data[call_id] = span_data
        self._set_current_context_data(span_data)

    def on_lm_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ) -> None:
        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
        )

    def on_tool_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        span_data = self._collect_common_span_data(instance, inputs)
        self._map_call_id_to_span_data[call_id] = span_data
        self._set_current_context_data(span_data)

    def on_tool_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ) -> None:
        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
        )

    def flush(self) -> None:
        """Sends pending Opik data to the backend"""
        self._opik_client.flush()

    def _set_current_context_data(self, value: SpanOrTraceData) -> None:
        if isinstance(value, span.SpanData):
            self._context_storage.add_span_data(value)
        elif isinstance(value, trace.TraceData):
            self._context_storage.set_trace_data(value)
        else:
            raise ValueError(f"Invalid context type: {type(value)}")

    def _get_current_context_data(self) -> Optional[SpanOrTraceData]:
        if span_data := self._context_storage.top_span_data():
            return span_data
        return self._context_storage.get_trace_data()

    def _get_span_type(self, instance: Any) -> types.SpanType:
        if isinstance(instance, dspy.Predict):
            return "llm"
        elif isinstance(instance, dspy.LM):
            return "llm"
        elif isinstance(instance, dspy.Tool):
            return "tool"
        return "general"

    def _get_opik_metadata(self, instance: Any) -> Dict[str, Any]:
        graph = None
        if self.log_graph and isinstance(instance, dspy.Module):
            try:
                graph = build_mermaid_graph_from_module(instance)
            except Exception:
                LOGGER.warning("Unable to generate graph from DSPy module")

        if graph:
            return {
                **self._origins_metadata,
                **{
                    "_opik_graph_definition": {
                        "format": "mermaid",
                        "data": graph,
                    }
                },
            }
        else:
            return self._origins_metadata
