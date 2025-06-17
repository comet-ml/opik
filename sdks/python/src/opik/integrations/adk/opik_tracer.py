import contextvars
import functools
import logging
from typing import Any, Dict, List, Optional, Set, Union, Tuple

from google.adk.agents.callback_context import CallbackContext
from google.adk.models import LlmRequest, LlmResponse, lite_llm
from google.adk.tools.base_tool import BaseTool
from google.adk.tools.tool_context import ToolContext

from opik import context_storage
from opik.decorator import arguments_helpers, span_creation_handler
from opik.api_objects import opik_client, span, trace
from opik.types import DistributedTraceHeadersDict
from . import helpers as adk_helpers, litellm_wrappers, llm_response_wrapper

LOGGER = logging.getLogger(__name__)

SpanOrTraceData = Union[span.SpanData, trace.TraceData]


def _get_info_from_adk_session(
    callback_context: CallbackContext,
) -> Tuple[Optional[str], Dict[str, Any]]:
    try:
        session = callback_context._invocation_context.session
        return session.id, {"user_id": session.user_id, "app_name": session.app_name}
    except Exception:
        LOGGER.error(
            "Failed to get session information from ADK callback context", exc_info=True
        )
        return None, {}


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
        self._client = opik_client.get_client_cached()

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

        _patch_adk()

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
        self, callback_context: CallbackContext, *args: Any, **kwargs: Any
    ) -> None:
        try:
            thread_id, session_metadata = _get_info_from_adk_session(callback_context)

            trace_metadata = self.metadata.copy()
            trace_metadata["adk_invocation_id"] = callback_context.invocation_id
            trace_metadata.update(session_metadata)

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
        self, callback_context: CallbackContext, *args: Any, **kwargs: Any
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
        callback_context: CallbackContext,
        llm_request: LlmRequest,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            input = adk_helpers.convert_adk_base_model_to_dict(llm_request)

            provider, model = litellm_wrappers.parse_provider_and_model(
                llm_request.model
            )

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
        callback_context: CallbackContext,
        llm_response: LlmResponse,
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
        provider = None
        usage = None
        output = None

        try:
            output = adk_helpers.convert_adk_base_model_to_dict(llm_response)
            usage_data = llm_response_wrapper.pop_llm_usage_data(output)
            if usage_data is not None:
                model = usage_data.model
                provider = usage_data.provider
                usage = usage_data.opik_usage
        except Exception as e:
            LOGGER.debug(
                f"Error converting LlmResponse to dict or extracting usage data, reason: {e}",
                exc_info=True,
            )

        self._last_model_output = output

        try:
            span_data = self._context_storage.top_span_data()
            assert span_data is not None

            if span_data.id in self._opik_created_spans:
                span_data.update(
                    output=output,
                    usage=usage,
                    model=model,
                    provider=provider,
                )
                self._end_current_span()
                self._opik_created_spans.discard(span_data.id)
        except Exception as e:
            LOGGER.error(f"Failed during after_model_callback(): {e}", exc_info=True)

    def before_tool_callback(
        self,
        tool: BaseTool,
        args: Dict[str, Any],
        tool_context: ToolContext,
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
        tool: BaseTool,
        args: Dict[str, Any],
        tool_context: ToolContext,
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


@functools.lru_cache()
def _patch_adk() -> None:
    # monkey patch LLMResponse to store usage_metadata
    old_function = LlmResponse.create
    create_wrapper = llm_response_wrapper.LlmResponseCreateWrapper(old_function)
    LlmResponse.create = create_wrapper

    if hasattr(lite_llm, "LiteLLMClient") and hasattr(
        lite_llm.LiteLLMClient, "acompletion"
    ):
        lite_llm.LiteLLMClient.acompletion = (
            litellm_wrappers.litellm_client_acompletion_decorator(
                lite_llm.LiteLLMClient.acompletion
            )
        )
    if hasattr(lite_llm, "_model_response_to_generate_content_response"):
        lite_llm._model_response_to_generate_content_response = (
            litellm_wrappers.generate_content_response_decorator(
                lite_llm._model_response_to_generate_content_response
            )
        )
