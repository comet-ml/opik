from typing import Any, Dict, Optional

from dspy.utils.callback import BaseCallback

from opik import context_storage, opik_context
from opik.api_objects import opik_client, span, trace
from opik.decorator import error_info_collector


class OpikCallback(BaseCallback):

    def __init__(
        self,
        project_name: Optional[str] = None,
    ):
        self._opik_trace_data: Optional[trace.TraceData] = None
        self._project_name = project_name
        self._opik_client = opik_client.Opik(
            _use_batching=True,
            project_name=project_name,
        )

    def on_module_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ):
        print(f"on_module_start() is called with call_id: {call_id}, instance: {instance.__class__.__name__}, inputs: {inputs}")

        if self._opik_trace_data is not None:
            return

        existing_trace_data = opik_context.get_current_trace_data()
        if existing_trace_data:
            self._opik_trace_data = existing_trace_data
        else:
            trace_data = trace.TraceData(
                name=instance.__class__.__name__,
                metadata={"created_from": "dspy"},
                project_name=self._project_name,
            )
            self._opik_trace_data = trace_data

    def on_module_end(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
    ):
        print(f"on_module_end() is called with call_id: {call_id}, outputs: {outputs}, exception: {exception}")

        if self._opik_trace_data is None:
            return

        if exception:
            error_info = error_info_collector.collect(exception)
            self._opik_trace_data.update(error_info=error_info)

        self._opik_trace_data.init_end_time()
        self._opik_client.trace(**self._opik_trace_data.__dict__)
        self._opik_trace_data = None

    def on_lm_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ):
        print(f"LM is called with inputs: {inputs}")

        span_data = span.SpanData(
            trace_id=self._opik_trace_data.id,
            name=instance.__class__.__name__,
            parent_span_id=None,
            type="llm",
            input=inputs,
            project_name=self._opik_trace_data.project_name,
        )
        context_storage.add_span_data(span_data)


    def on_lm_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ):
        print(f"LM is finished with outputs: {outputs}")

        span_data = context_storage.pop_span_data()

        if exception:
            error_info = error_info_collector.collect(exception)
            span_data.update(error_info=error_info)

        span_data.update(output={"output": outputs}).init_end_time()

        self._opik_client.span(**span_data.__dict__)
