import contextvars
import logging
from typing import Any, Dict, List, Optional, Set, Union

import google.adk.agents
from google.adk.agents import callback_context
from google.adk import models
from google.adk.tools import base_tool
from google.adk.tools import tool_context

from opik import context_storage
from opik.decorator import arguments_helpers, span_creation_handler
from opik.api_objects import opik_client, span, trace
from opik.types import DistributedTraceHeadersDict

from . import (
    helpers as adk_helpers,
    callback_context_info_extractors,
    patchers,
)

from .patchers import (
    litellm_wrappers,
    llm_response_wrapper,
)
from .graph import mermaid_graph_builder

LOGGER = logging.getLogger(__name__)

SpanOrTraceData = Union[span.SpanData, trace.TraceData]


class LegacyOpikTracer:
    def __init__(
        self,
        name: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        project_name: Optional[str] = None,
        distributed_headers: Optional[DistributedTraceHeadersDict] = None,
    ):
        LOGGER.warning(
            "Legacy OpikTracer for google-adk < 1.3.0 is being used. We recommend upgrading to the recent version to automatically get the best experience from Opik integration."
        )
        self.name = name
        self.tags = tags
        self.metadata = metadata or {}
        self.metadata["created_from"] = "google-adk"
        self.project_name = project_name
        self.distributed_headers = distributed_headers

        self._init_internal_attributes()

    def _init_internal_attributes(self) -> None:
        self._last_model_output: Optional[Dict[str, Any]] = None

        # Use OpikContextStorage instance instead of global context storage module
        # in case we need to use different context storage for ADK in the future
        self._context_storage = context_storage.get_current_context_instance()

        self._opik_created_spans: Set[str] = (
            set()
        )  # TODO: use contextvar set for a more reliable clean-up?

        self._current_trace_created_by_opik_tracer: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("current_trace_created_by_opik_tracer", default=None)

        self._opik_client = opik_client.get_client_cached()

        patchers.patch_adk(self._opik_client)

    def flush(self) -> None:
        self._opik_client.flush()

    def _end_current_trace(self) -> None:
        trace_data = self._context_storage.pop_trace_data()
        assert trace_data is not None
        trace_data.init_end_time()
        self._opik_client.trace(**trace_data.as_parameters)

    def _end_current_span(
        self,
    ) -> None:
        span_data = self._context_storage.pop_span_data()
        assert span_data is not None
        span_data.init_end_time()
        self._opik_client.span(**span_data.as_parameters)

    def _start_span(self, span_data: span.SpanData) -> None:
        self._context_storage.add_span_data(span_data)
        self._opik_created_spans.add(span_data.id)

        if self._opik_client.config.log_start_trace_span:
            self._opik_client.span(**span_data.as_start_parameters)

    def _start_trace(self, trace_data: trace.TraceData) -> None:
        self._context_storage.set_trace_data(trace_data)
        self._current_trace_created_by_opik_tracer.set(trace_data.id)

        if self._opik_client.config.log_start_trace_span:
            self._opik_client.trace(**trace_data.as_start_parameters)

    def _set_current_context_data(self, value: SpanOrTraceData) -> None:
        if isinstance(value, span.SpanData):
            self._context_storage.add_span_data(value)
        elif isinstance(value, trace.TraceData):
            self._context_storage.set_trace_data(value)
        else:
            raise ValueError(f"Invalid context type: {type(value)}")

    def before_agent_callback(
        self,
        callback_context: callback_context.CallbackContext,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            thread_id, session_metadata = (
                callback_context_info_extractors.try_get_session_info(callback_context)
            )

            trace_metadata = self.metadata.copy()
            trace_metadata["adk_invocation_id"] = callback_context.invocation_id
            trace_metadata.update(session_metadata)

            _try_add_agent_graph_to_metadata(trace_metadata, callback_context)

            if callback_context.user_content is not None:
                user_input = adk_helpers.convert_adk_base_model_to_dict(
                    callback_context.user_content
                )
            else:
                user_input = None
            name = self.name or callback_context.agent_name

            current_trace_data = self._context_storage.get_trace_data()
            if current_trace_data is None:  # todo: support distributed headers
                current_trace = trace.TraceData(
                    name=name,
                    project_name=self.project_name,
                    metadata=trace_metadata,
                    thread_id=thread_id,
                    input=user_input,
                    tags=self.tags,
                )

                self._start_trace(trace_data=current_trace)
            else:
                start_span_arguments = arguments_helpers.StartSpanParameters(
                    name=name,
                    project_name=self.project_name,
                    metadata=trace_metadata,
                    tags=self.tags,
                    input=user_input,
                    type="general",
                )
                _, opik_span_data = (
                    span_creation_handler.create_span_respecting_context(
                        start_span_arguments=start_span_arguments,
                        distributed_trace_headers=None,
                        opik_context_storage=self._context_storage,
                    )
                )

                self._start_span(span_data=opik_span_data)
        except Exception as e:
            LOGGER.error(f"Failed during before_agent_callback(): {e}", exc_info=True)

    def after_agent_callback(
        self,
        callback_context: callback_context.CallbackContext,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            output = self._last_model_output

            if (span_data := self._context_storage.top_span_data()) is not None:
                if span_data.id in self._opik_created_spans:
                    span_data.update(output=output)
                    self._end_current_span()
                    self._opik_created_spans.discard(span_data.id)
            else:
                trace_data = self._context_storage.get_trace_data()
                assert trace_data is not None

                if trace_data.id == self._current_trace_created_by_opik_tracer.get():
                    trace_data.update(output=output)
                    self._end_current_trace()
                    self._current_trace_created_by_opik_tracer.set(None)
                    self._last_model_output = None
        except Exception as e:
            LOGGER.error(f"Failed during after_agent_callback(): {e}", exc_info=True)

    def before_model_callback(
        self,
        callback_context: callback_context.CallbackContext,
        llm_request: models.LlmRequest,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            input = adk_helpers.convert_adk_base_model_to_dict(llm_request)

            provider, model = litellm_wrappers.parse_provider_and_model(
                llm_request.model
            )
            if provider is None:
                provider = adk_helpers.get_adk_provider()

            _, span_data = span_creation_handler.create_span_respecting_context(
                start_span_arguments=arguments_helpers.StartSpanParameters(
                    name=llm_request.model,
                    project_name=self.project_name,
                    metadata=self.metadata,
                    type="llm",
                    model=model,
                    provider=provider,
                    input=input,
                ),
                distributed_trace_headers=None,
                opik_context_storage=self._context_storage,
            )

            self._start_span(span_data=span_data)

        except Exception as e:
            LOGGER.error(f"Failed during before_model_callback(): {e}", exc_info=True)

    def after_model_callback(
        self,
        callback_context: callback_context.CallbackContext,
        llm_response: models.LlmResponse,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            # Ignore partial chunks, ADK will call this method with the full response at the end
            if llm_response.partial is True:
                return
        except Exception:
            LOGGER.debug("Error checking for partial chunks", exc_info=True)

        if adk_helpers.has_empty_text_part_content(llm_response):
            # fix for gemini-2.5-flash-preview which in streaming mode can return responses with empty content:
            # {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"}}],...}}
            return

        model = None
        usage = None
        output = None

        try:
            span_data = self._context_storage.top_span_data()
            assert span_data is not None

            try:
                output = adk_helpers.convert_adk_base_model_to_dict(llm_response)
                self._last_model_output = output

                usage_data = llm_response_wrapper.pop_llm_usage_data(
                    output, span_data.provider
                )
                if usage_data is not None:
                    model = usage_data.model
                    usage = usage_data.opik_usage
            except Exception as e:
                LOGGER.debug(
                    f"Error converting LlmResponse to dict or extracting usage data, reason: {e}",
                    exc_info=True,
                )

            if span_data.id in self._opik_created_spans:
                span_data.update(
                    name=model,
                    output=output,
                    usage=usage,
                    model=model,
                )
                self._end_current_span()
                self._opik_created_spans.discard(span_data.id)
        except Exception as e:
            LOGGER.error(f"Failed during after_model_callback(): {e}", exc_info=True)

    def before_tool_callback(
        self,
        tool: base_tool.BaseTool,
        args: Dict[str, Any],
        tool_context: tool_context.ToolContext,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            metadata = {
                "function_call_id": tool_context.function_call_id,
                **self.metadata,
            }

            _, span_data = span_creation_handler.create_span_respecting_context(
                start_span_arguments=arguments_helpers.StartSpanParameters(
                    name=tool.name,
                    project_name=self.project_name,
                    metadata=metadata,
                    type="tool",
                    input=args,
                ),
                distributed_trace_headers=None,
                opik_context_storage=self._context_storage,
            )

            self._start_span(span_data=span_data)

        except Exception as e:
            LOGGER.error(f"Failed during before_tool_callback(): {e}", exc_info=True)

    def after_tool_callback(
        self,
        tool: base_tool.BaseTool,
        args: Dict[str, Any],
        tool_context: tool_context.ToolContext,
        tool_response: Any,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            current_span_data = self._context_storage.top_span_data()
            assert current_span_data is not None

            output = (
                tool_response
                if isinstance(tool_response, dict)
                else {"output": tool_response}
            )
            if current_span_data.id in self._opik_created_spans:
                current_span_data.update(output=output)
                self._end_current_span()
                self._opik_created_spans.discard(current_span_data.id)
        except Exception as e:
            LOGGER.error(f"Failed during after_tool_callback(): {e}", exc_info=True)

    def __getstate__(self) -> Dict[str, Any]:
        state = self.__dict__.copy()

        state.pop("_last_model_output", None)
        state.pop("_opik_client", None)
        state.pop("_context_storage", None)
        state.pop("_current_trace_created_by_opik_tracer", None)
        state.pop("_opik_created_spans", None)

        return state

    def __setstate__(self, state: Dict[str, Any]) -> None:
        self.__dict__.update(state)
        self._init_internal_attributes()


def _try_add_agent_graph_to_metadata(
    metadata: Dict[str, Any], callback_context: callback_context.CallbackContext
) -> None:
    current_agent: Optional[google.adk.agents.BaseAgent] = (
        callback_context_info_extractors.try_get_current_agent_instance(
            callback_context
        )
    )

    if current_agent is None:
        return

    try:
        metadata["_opik_graph_definition"] = {
            "format": "mermaid",
            "data": mermaid_graph_builder.build_mermaid_graph_definition(
                current_agent.root_agent
            ),
        }
    except Exception:
        LOGGER.error("Failed to build mermaid graph for agent.", exc_info=True)
