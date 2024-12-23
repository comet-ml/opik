from contextvars import ContextVar
from typing import Any, Dict, Optional, Set

from dspy.utils.callback import BaseCallback

from opik import context_storage, opik_context
from opik.api_objects import helpers, opik_client, span, trace
from opik.decorator import error_info_collector


_OPIK_CONTEXT = ContextVar("opik_context", default=None)


class OpikCallback(BaseCallback):

    def __init__(
        self,
        project_name: Optional[str] = None,
    ):
        self._map_call_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_call_id_to_trace_data: Dict[str, trace.TraceData] = {}
        self._externally_created_traces_ids: Set[str] = set()

        self._current_context: ContextVar[Optional[span.SpanData]] = _OPIK_CONTEXT

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
    ) -> None:
        print(f"on_module_start() is called with call_id: {call_id}, instance: {instance.__class__.__name__}, inputs: {inputs}")

        if current_context := self._current_context.get():
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_context,
                instance=instance,
                inputs=inputs,
            )
            return

        if current_span_data := opik_context.get_current_span_data():
            self._current_context.set(current_span_data)
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_span_data,
                instance=instance,
                inputs=inputs,
            )
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

        # trace_data = trace.TraceData(
        #     name=instance.__class__.__name__,
        #     metadata={"created_from": "dspy"},
        #     project_name=self._project_name,
        # )

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
        self._externally_created_traces_ids.add(span_data.trace_id)

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
        self._current_context.set(span_data)
        self._map_call_id_to_span_data[call_id] = span_data
        self._externally_created_traces_ids.add(current_trace_data.id)

    def _initialize_span_and_trace_from_scratch(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        trace_data = trace.TraceData(
            name=instance.__class__.__name__,
            input=inputs,
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

        self._map_call_id_to_span_data[call_id] = span_data

    def on_module_end(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
    ):
        print(f"on_module_end() is called with call_id: {call_id}, outputs: {outputs}, exception: {exception}")

        # if self._opik_trace_data is None:
        #     return
        #
        # if exception:
        #     error_info = error_info_collector.collect(exception)
        #     self._opik_trace_data.update(error_info=error_info)
        #
        # self._opik_trace_data.init_end_time()
        # self._opik_client.trace(**self._opik_trace_data.__dict__)
        # self._opik_trace_data = None

    def on_lm_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ):
        print(f"LM is called with inputs: {inputs}")

        current_span_data = self._map_call_id_to_span_data.get(call_id)
        # assert current_span_data is not None

        # todo handle provider+model
        # elif isinstance(instance, dspy.Predict):
        #     return SpanType.LLM

        span_data = span.SpanData(
            trace_id=current_span_data.trace_id,
            name=instance.__class__.__name__,
            parent_span_id=current_span_data.id,
            type="llm",
            input=inputs,
            # project_name=self._opik_trace_data.project_name,
            # provider="openai",
            # model="gpt-3.5-turbo",
        )
        # context_storage.add_span_data(span_data)


    def on_lm_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ):
        print(f"LM is finished with outputs: {outputs}")

        # span_data = context_storage.pop_span_data()
        #
        # if exception:
        #     error_info = error_info_collector.collect(exception)
        #     span_data.update(error_info=error_info)
        #
        # span_data.update(output={"output": outputs}).init_end_time()
        #
        # self._opik_client.span(**span_data.__dict__)
