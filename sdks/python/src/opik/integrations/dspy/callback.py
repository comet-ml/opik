from contextvars import ContextVar, Token
from typing import Any, Dict, Optional, Union

import dspy
from dspy.utils.callback import BaseCallback

from opik import opik_context, types
from opik.api_objects import helpers, span, trace
from opik.api_objects.opik_client import get_client_cached
from opik.decorator import error_info_collector

ContextType = Union[span.SpanData, trace.TraceData]


class OpikCallback(BaseCallback):
    def __init__(
        self,
        project_name: Optional[str] = None,
    ):
        self._map_call_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_call_id_to_trace_data: Dict[str, trace.TraceData] = {}
        self._map_span_id_or_trace_id_to_token: Dict[str, Token] = {}

        self._origins_metadata = {"created_from": "dspy"}

        self._current_callback_context: ContextVar[Optional[ContextType]] = ContextVar(
            "opik_context", default=None
        )

        self._project_name = project_name

        self._opik_client = get_client_cached()

    def on_module_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        if current_callback_context_data := self._current_callback_context.get():
            if isinstance(current_callback_context_data, span.SpanData):
                self._attach_span_to_existing_span(
                    call_id=call_id,
                    current_span_data=current_callback_context_data,
                    instance=instance,
                    inputs=inputs,
                )
            else:
                self._attach_span_to_existing_trace(
                    call_id=call_id,
                    current_trace_data=current_callback_context_data,
                    instance=instance,
                    inputs=inputs,
                )
            return

        if current_span_data := opik_context.get_current_span_data():
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_span_data,
                instance=instance,
                inputs=inputs,
            )
            new_span_data = self._map_call_id_to_span_data[call_id]
            self._callback_context_set(new_span_data)
            return

        if current_trace_data := opik_context.get_current_trace_data():
            self._attach_span_to_existing_trace(
                call_id=call_id,
                current_trace_data=current_trace_data,
                instance=instance,
                inputs=inputs,
            )
            new_span_data = self._map_call_id_to_span_data[call_id]
            self._callback_context_set(new_span_data)
            return

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
            metadata=self._origins_metadata,
        )
        self._map_call_id_to_span_data[call_id] = span_data

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
            metadata=self._origins_metadata,
        )
        self._map_call_id_to_span_data[call_id] = span_data

    def _start_trace(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        trace_data = trace.TraceData(
            name=instance.__class__.__name__,
            input=inputs,
            metadata={"created_from": "dspy"},
            project_name=self._project_name,
        )
        self._map_call_id_to_trace_data[call_id] = trace_data
        self._callback_context_set(trace_data)

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
            self._opik_client.trace(**trace_data.__dict__)

            # remove trace data from context
            if token := self._map_span_id_or_trace_id_to_token.pop(trace_data.id, None):
                self._current_callback_context.reset(token)

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
            self._opik_client.span(**span_data.__dict__)

            # remove span data from context
            if token := self._map_span_id_or_trace_id_to_token.pop(span_data.id, None):
                self._current_callback_context.reset(token)

    def on_lm_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        current_callback_context_data = self._current_callback_context.get()
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

        provider, model = instance.model.split(r"/", 1)
        span_type = self._get_span_type(instance)

        span_data = span.SpanData(
            trace_id=trace_id,
            name=instance.__class__.__name__,
            parent_span_id=parent_span_id,
            type=span_type,
            input=inputs,
            project_name=project_name,
            provider=provider,
            model=model,
            metadata=self._origins_metadata,
        )
        self._map_call_id_to_span_data[call_id] = span_data

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
        self._end_trace(call_id=call_id)

    def flush(self) -> None:
        """Sends pending Opik data to the backend"""
        self._opik_client.flush()

    def _callback_context_set(self, value: ContextType) -> None:
        token = self._current_callback_context.set(value)
        self._map_span_id_or_trace_id_to_token[value.id] = token

    def _get_span_type(self, instance: Any) -> types.SpanType:
        if isinstance(instance, dspy.Predict):
            return "llm"
        elif isinstance(instance, dspy.LM):
            return "llm"
        return "general"
