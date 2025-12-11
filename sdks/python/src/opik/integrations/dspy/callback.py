from typing import Any, Dict, Optional, Tuple, Union
import logging

import dspy
from dspy.utils import callback as dspy_callback

from opik import context_storage, opik_context, tracing_runtime_config
from opik import llm_usage
from opik.api_objects import helpers, span, trace, opik_client
from opik.decorator import error_info_collector

from .graph import build_mermaid_graph_from_module
from .parsers import LMHistoryInfo, extract_lm_info_from_history, get_span_type

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
        # Store (lm_instance, expected_messages) for extracting usage and verifying correct history entry
        self._map_call_id_to_lm_info: Dict[str, Tuple[Any, Optional[Any]]] = {}

        self._origins_metadata: Dict[str, Any] = {"created_from": "dspy"}

        self._context_storage = context_storage.OpikContextStorage()

        self._project_name = project_name
        self.log_graph = log_graph

        self._opik_client = opik_client.get_client_cached()

    def _skip_tracking(self) -> bool:
        return not tracing_runtime_config.is_tracing_active()

    def on_module_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        if self._skip_tracking():
            return

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
        span_type = get_span_type(instance)

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
        span_type = get_span_type(instance)

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

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
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

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
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
            if tracing_runtime_config.is_tracing_active():
                trace_data.init_end_time()
                self._opik_client.trace(**trace_data.as_parameters)

            if self._context_storage.get_trace_data() == trace_data:
                self._context_storage.set_trace_data(None)

    def _end_span(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
        usage: Optional[llm_usage.OpikUsage] = None,
        extra_metadata: Optional[Dict[str, Any]] = None,
        actual_provider: Optional[str] = None,
        total_cost: Optional[float] = None,
    ) -> None:
        if span_data := self._map_call_id_to_span_data.pop(call_id, None):
            if exception:
                error_info = error_info_collector.collect(exception)
                span_data.update(error_info=error_info)

            # Prepare the update dict
            update_kwargs: Dict[str, Any] = {
                "output": {"output": outputs},
                "usage": usage,
                "total_cost": total_cost,
            }

            # Handle LLM routers like OpenRouter that return the actual serving provider
            if (
                actual_provider is not None
                and span_data.provider is not None
                and span_data.provider.lower() != actual_provider.lower()
            ):
                # Store the original provider (e.g., "openrouter") in metadata
                if extra_metadata is None:
                    extra_metadata = {}
                extra_metadata["llm_router"] = span_data.provider
                # Update to the actual provider for accurate cost tracking
                update_kwargs["provider"] = actual_provider

            update_kwargs["metadata"] = extra_metadata

            span_data.update(**update_kwargs).init_end_time()
            if tracing_runtime_config.is_tracing_active():
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

        span_type = get_span_type(instance)

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

        # Store LM instance and expected messages for extracting usage
        self._map_call_id_to_lm_info[call_id] = (
            instance,
            inputs.get("messages"),
        )

        self._set_current_context_data(span_data)

    def on_lm_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ) -> None:
        lm_info = self._extract_lm_info_from_history(call_id)

        # Add cache_hit to span metadata only when we have a definitive value
        extra_metadata = (
            {"cache_hit": lm_info.cache_hit} if lm_info.cache_hit is not None else None
        )

        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
            usage=lm_info.usage,
            extra_metadata=extra_metadata,
            actual_provider=lm_info.actual_provider,
            total_cost=lm_info.total_cost,
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

    def _extract_lm_info_from_history(self, call_id: str) -> LMHistoryInfo:
        """
        Extract token usage, cache status, actual provider, and cost from the LM's history.

        DSPy stores usage information in the LM's history after each call.
        We verify the history entry matches our expected messages to handle
        potential race conditions with concurrent LM calls.

        For routers like OpenRouter, the response contains the actual provider
        that served the request (e.g., "Novita", "Together"), which differs from
        the router name used in the model string (e.g., "openrouter").

        The cost field is provided by providers like OpenRouter and includes
        accurate pricing for all token types (reasoning, cache, multimodal).

        Returns:
            LMHistoryInfo containing usage, cache_hit, actual_provider, and total_cost.
        """
        lm_info = self._map_call_id_to_lm_info.pop(call_id, None)
        if lm_info is None:
            return LMHistoryInfo(
                usage=None, cache_hit=None, actual_provider=None, total_cost=None
            )

        lm_instance, expected_messages = lm_info
        return extract_lm_info_from_history(lm_instance, expected_messages)

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
