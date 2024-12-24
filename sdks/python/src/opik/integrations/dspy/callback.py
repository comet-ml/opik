from contextvars import ContextVar, Token
from typing import Any, Dict, Optional

from dspy.utils.callback import BaseCallback

from opik import opik_context
from opik.api_objects import helpers, opik_client, span, trace
from opik.decorator import error_info_collector


class OpikCallback(BaseCallback):

    def __init__(
        self,
        project_name: Optional[str] = None,
    ):
        self._map_call_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_call_id_to_trace_data: Dict[str, trace.TraceData] = {}
        self._map_span_id_to_token: Dict[str, Token] = {}

        self._current_callback_context: ContextVar[Optional[span.SpanData]] = ContextVar("opik_context", default=None)

        self._project_name = project_name

        self._opik_client = opik_client.Opik(
            project_name=project_name,
            _use_batching=True,
        )

    def on_module_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        print(
            f"on_module_start() is called with call_id: {call_id}, instance: {instance.__class__.__name__}")

        if current_callback_context_data := self._current_callback_context.get():
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_callback_context_data,
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
            return

        self._initialize_span_and_trace_from_scratch(
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

        span_data = span.SpanData(
            trace_id=current_span_data.trace_id,
            parent_span_id=current_span_data.id,
            name=instance.__class__.__name__,
            input=inputs,
            project_name=project_name,
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

        span_data = span.SpanData(
            trace_id=current_trace_data.id,
            parent_span_id=None,
            name=instance.__class__.__name__,
            input=inputs,
            project_name=project_name,
        )
        self._callback_context_set(span_data)
        self._map_call_id_to_span_data[call_id] = span_data

    def _initialize_span_and_trace_from_scratch(
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

        span_data = span.SpanData(
            trace_id=trace_data.id,
            parent_span_id=None,
            name=instance.__class__.__name__,
            input=inputs,
            project_name=self._project_name,
        )
        self._callback_context_set(span_data)
        self._map_call_id_to_span_data[call_id] = span_data

    def on_module_end(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
    ):
        print(f"on_module_end() is called with call_id: {call_id}, outputs: {outputs}, exception: {exception}")

        span_data = self._map_call_id_to_span_data.pop(call_id)
        trace_data = self._map_call_id_to_trace_data.pop(call_id, None)

        if exception:
            error_info = error_info_collector.collect(exception)
            span_data.update(error_info=error_info)

        span_data.update(output={"output": outputs}).init_end_time()
        self._opik_client.span(**span_data.__dict__)

        # remove span data from context
        if token := self._map_span_id_to_token.pop(span_data.id, None):
            self._current_callback_context.reset(token)

        if trace_data is not None:
            trace_data.init_end_time()
            self._opik_client.trace(**trace_data.__dict__)

    def on_lm_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ):
        print(f"LM is called with inputs: {inputs}")

        current_context = self._current_callback_context.get()
        assert current_context is not None

        # todo handle provider+model
        # elif isinstance(instance, dspy.Predict):
        #     return SpanType.LLM

        span_data = span.SpanData(
            trace_id=current_context.trace_id,
            name=instance.__class__.__name__,
            parent_span_id=current_context.id,
            type="llm",
            input=inputs,
            project_name=current_context.project_name,
            # provider="openai",
            # model="gpt-3.5-turbo",
        )
        self._map_call_id_to_span_data[call_id] = span_data

    def on_lm_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ):
        print(f"LM is finished with outputs: {outputs}")

        span_data = self._map_call_id_to_span_data.pop(call_id)

        if exception:
            error_info = error_info_collector.collect(exception)
            span_data.update(error_info=error_info)

        span_data.update(output={"output": outputs}).init_end_time()

        self._opik_client.span(**span_data.__dict__)

    def flush(self) -> None:
        self._opik_client.flush()

    def _callback_context_set(self, value: span.SpanData) -> None:
        token = self._current_callback_context.set(value)
        self._map_span_id_to_token[value.id] = token
