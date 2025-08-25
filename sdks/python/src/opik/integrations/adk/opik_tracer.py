import logging
from typing import Any, Dict, List, Optional, Union

import google.adk.agents
from google.adk.agents import callback_context
from google.adk import models
from google.adk.tools import base_tool
from google.adk.tools import tool_context

from opik import context_storage
from opik.api_objects import opik_client, span, trace
from opik.types import DistributedTraceHeadersDict
from opik.decorator import span_creation_handler, arguments_helpers

from . import (
    helpers as adk_helpers,
    callback_context_info_extractors,
    patchers,
)
from .patchers import (
    litellm_wrappers,
    llm_response_wrapper,
    adk_tracer_for_opik_context_management,
)
from .graph import mermaid_graph_builder

LOGGER = logging.getLogger(__name__)

SpanOrTraceData = Union[span.SpanData, trace.TraceData]


class OpikTracer:
    def __init__(
        self,
        name: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        project_name: Optional[str] = None,
        distributed_headers: Optional[DistributedTraceHeadersDict] = None,
    ):
        self.name = name
        self.tags = tags
        self.metadata = metadata or {}
        self.metadata["created_from"] = "google-adk"
        self.project_name = project_name
        self.distributed_headers = distributed_headers

        self._init_internal_attributes()

    def _init_internal_attributes(self) -> None:
        self._last_model_output: Optional[Dict[str, Any]] = None
        self._opik_client = opik_client.get_client_cached()

        patchers.patch_adk(self._opik_client)

    def flush(self) -> None:
        self._opik_client.flush()

    def before_agent_callback(
        self,
        callback_context: callback_context.CallbackContext,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            current_trace = context_storage.get_trace_data()
            current_span = context_storage.top_span_data()

            thread_id, session_metadata = (
                callback_context_info_extractors.try_get_session_info(callback_context)
            )

            agent_metadata = self.metadata.copy()
            agent_metadata["adk_invocation_id"] = callback_context.invocation_id
            agent_metadata.update(session_metadata)

            _try_add_agent_graph_to_metadata(agent_metadata, callback_context)

            if callback_context.user_content is not None:
                user_input = adk_helpers.convert_adk_base_model_to_dict(
                    callback_context.user_content
                )
            else:
                user_input = None

            name = self.name or callback_context.agent_name

            if current_span is not None:
                current_span.update(
                    name=name,
                    metadata={**agent_metadata},
                    input=user_input,
                    tags=self.tags,
                    project_name=self.project_name,
                )
            elif current_trace is not None:
                current_trace.update(
                    name=name,
                    metadata={**agent_metadata},
                    input=user_input,
                    tags=self.tags,
                    thread_id=thread_id,
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    f"No current span or trace found in context for agent: {callback_context.agent_name}"
                )

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
            # Debug logging for callback invocation
            current_span = context_storage.top_span_data()
            current_trace = context_storage.get_trace_data()
            if current_span is not None:
                current_span.update(
                    output=output,
                    project_name=self.project_name,
                )
            elif current_trace is not None:
                current_trace.update(
                    output=output,
                    project_name=self.project_name,
                )
                self._last_model_output = None
            else:
                LOGGER.warning(
                    "No current span or trace found in context for agent output update"
                )
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

            # ADK runs `before_model_callback` before running `start_as_current_span` function for the LLM call,
            # which makes it impossible to update the Opik span from this method.
            # So we create a span manually here. This flow is handled inside ADKTracerWrapper.
            _, span_data = span_creation_handler.create_span_respecting_context(
                start_span_arguments=arguments_helpers.StartSpanParameters(
                    name=adk_tracer_for_opik_context_management.NAME_OF_LLM_SPAN_JUST_STARTED_FROM_OPIK_TRACER,
                    project_name=self.project_name,
                    metadata=self.metadata,
                    type="llm",
                    model=model,
                    provider=provider,
                    input=input,
                ),
                distributed_trace_headers=None,
            )

            context_storage.add_span_data(span_data)
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

        try:
            model = None
            usage = None
            output = None

            if adk_helpers.has_empty_text_part_content(llm_response):
                return

            current_span = context_storage.top_span_data()
            if current_span is None:
                LOGGER.warning(
                    "No current span found in context for model output update"
                )
                return

            try:
                output = adk_helpers.convert_adk_base_model_to_dict(llm_response)
                usage_data = llm_response_wrapper.pop_llm_usage_data(
                    output, current_span.provider
                )
                if usage_data is not None:
                    model = usage_data.model
                    usage = usage_data.opik_usage
            except Exception as e:
                LOGGER.debug(
                    f"Error converting LlmResponse to dict or extracting usage data, reason: {e}",
                    exc_info=True,
                )

            current_span.update(
                output=output,
                name=model or current_span.model,
                type="llm",
                model=model,
                usage=usage,
                project_name=self.project_name,
            )
            context_storage.pop_span_data(ensure_id=current_span.id)
            current_span.init_end_time()
            # We close this span manually because otherwise ADK will close it too late,
            # and it will also add tool spans inside of it, which we want to avoid.
            self._opik_client.span(**current_span.as_parameters)
            self._last_model_output = output

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
            current_span = context_storage.top_span_data()

            tool_metadata = {
                "function_call_id": tool_context.function_call_id,
                **self.metadata,
            }

            # Update existing span with tool information
            if current_span is not None:
                current_span.update(
                    name=tool.name,
                    type="tool",
                    input=args,
                    metadata={**tool_metadata},
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    f"No current span found in context for tool: {tool.name}"
                )

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
            # Debug logging for callback invocation
            current_span = context_storage.top_span_data()

            output = (
                tool_response
                if isinstance(tool_response, dict)
                else {"output": tool_response}
            )

            # Update existing span with tool output
            if current_span is not None:
                current_span.update(
                    output=output,
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    f"No current span found in context for tool output update: {tool.name}"
                )
        except Exception as e:
            LOGGER.error(f"Failed during after_tool_callback(): {e}", exc_info=True)

    def __getstate__(self) -> Dict[str, Any]:
        state = self.__dict__.copy()
        state.pop("_opik_client", None)
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
