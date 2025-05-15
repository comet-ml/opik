import logging
import uuid
from typing import Any, Dict, List, Optional, Union

from google.adk.agents.callback_context import CallbackContext
from google.adk.models import LlmRequest, LlmResponse
from google.adk.tools.base_tool import BaseTool
from google.adk.tools.tool_context import ToolContext
from opik import context_storage
from opik.api_objects import helpers, opik_client, trace, span
from opik.types import DistributedTraceHeadersDict, SpanType

from . import llm_response_wrapper
from . import helpers as adk_helpers

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
        self.project_name = project_name
        self.distributed_headers = distributed_headers
        self._client = opik_client.get_client_cached()

        self._last_model_output: Optional[Dict[str, Any]] = None

        # Use OpikContextStorage instance instead of global context storage module
        # in case we need to use different context storage for ADK in the future
        self._context_storage = context_storage.get_current_context_instance()

        self._opik_client = opik_client.get_client_cached()

        # monkey patch LLMResponse to store usage_metadata
        old_function = LlmResponse.create
        create_wrapper = llm_response_wrapper.LlmResponseCreateWrapper(old_function)
        LlmResponse.create = create_wrapper

    def _attach_span_to_existing_span(
        self,
        current_span_data: span.SpanData,
        name: str,
        input: Dict[str, Any],
        type: SpanType,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        project_name = helpers.resolve_child_span_project_name(
            parent_project_name=current_span_data.project_name,
            child_project_name=self.project_name,
        )

        span_data = span.SpanData(
            trace_id=current_span_data.trace_id,
            parent_span_id=current_span_data.id,
            name=name,
            input=input,
            type=type,
            project_name=project_name,
            metadata=self.metadata
            if metadata is None
            else {**self.metadata, **metadata},
        )
        self._set_current_context_data(span_data)

    def _attach_span_to_existing_trace(
        self,
        current_trace_data: trace.TraceData,
        name: str,
        input: Dict[str, Any],
        type: SpanType,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        project_name = helpers.resolve_child_span_project_name(
            parent_project_name=current_trace_data.project_name,
            child_project_name=self.project_name,
        )
        span_data = span.SpanData(
            trace_id=current_trace_data.id,
            parent_span_id=None,
            name=name,
            input=input,
            type=type,
            project_name=project_name,
            metadata=self.metadata
            if metadata is None
            else {**self.metadata, **metadata},
        )
        self._set_current_context_data(span_data)

    def _start_trace(
        self,
        new_trace_data: trace.TraceData,
    ) -> None:
        self._set_current_context_data(new_trace_data)

    def _end_current_trace(self) -> None:
        if (trace_data := self._context_storage.get_trace_data()) is not None:
            trace_data.init_end_time()
            self._opik_client.trace(**trace_data.__dict__)

            self._context_storage.set_trace_data(None)

    def _end_current_span(
        self,
    ) -> None:
        if (span_data := self._context_storage.top_span_data()) is not None:
            span_data.init_end_time()
            self._opik_client.span(**span_data.__dict__)

            self._context_storage.pop_span_data()

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
        if "opik_thread_id" in callback_context.state:
            thread_id = callback_context.state["opik_thread_id"]
        else:
            thread_id = str(uuid.uuid4())
            callback_context.state["opik_thread_id"] = thread_id

        trace_metadata = self.metadata.copy()
        trace_metadata["adk_invocation_id"] = callback_context.invocation_id

        user_input = adk_helpers.convert_adk_base_model_to_dict(
            callback_context.user_content
        )
        name = self.name or callback_context.agent_name

        if (current_span_data := self._context_storage.top_span_data()) is not None:
            self._attach_span_to_existing_span(
                current_span_data=current_span_data,
                name=name,
                input=user_input,
                type="general",
                metadata=self.metadata,
            )
        elif (current_trace_data := self._context_storage.get_trace_data()) is not None:
            self._attach_span_to_existing_trace(
                current_trace_data=current_trace_data,
                name=name,
                input=user_input,
                type="general",
                metadata=self.metadata,
            )
        else:
            new_trace_data = trace.TraceData(
                name=name,
                input=user_input,
                metadata=self.metadata,
                project_name=self.project_name,
                thread_id=thread_id,
            )
            self._start_trace(new_trace_data)

    def after_agent_callback(
        self, callback_context: CallbackContext, *args: Any, **kwargs: Any
    ) -> None:
        output = self._last_model_output

        if (span_data := self._context_storage.top_span_data()) is None:
            trace_data = self._context_storage.get_trace_data()
            assert trace_data is not None
            trace_data.update(output=output).init_end_time()
            self._end_current_trace()
            self._last_model_output = None
        else:
            span_data.update(output=output).init_end_time()
            self._end_current_span()

    def before_model_callback(
        self,
        callback_context: CallbackContext,
        llm_request: LlmRequest,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        input = adk_helpers.convert_adk_base_model_to_dict(llm_request)
        if (current_span_data := self._context_storage.top_span_data()) is not None:
            self._attach_span_to_existing_span(
                current_span_data=current_span_data,
                name=llm_request.model,
                input=input,
                type="llm",
                metadata=self.metadata,
            )
        else:
            current_trace_data = self._context_storage.get_trace_data()
            assert current_trace_data is not None
            self._attach_span_to_existing_trace(
                current_trace_data=current_trace_data,
                name=llm_request.model,
                input=input,
                type="llm",
                metadata=self.metadata,
            )

    def after_model_callback(
        self,
        callback_context: CallbackContext,
        llm_response: LlmResponse,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            # Ignore partial chunks, ADK will call this method with the full
            # response at the end
            if llm_response.partial is True:
                return
        except Exception:
            LOGGER.debug("Error checking for partial chunks", exc_info=True)

        output = adk_helpers.convert_adk_base_model_to_dict(llm_response)
        usage_data = llm_response_wrapper.pop_llm_usage_data(output)
        if usage_data is not None:
            model = usage_data.model
            provider = usage_data.provider
            usage = usage_data.opik_usage
        else:
            model = None
            provider = None
            usage = None

        self._last_model_output = output

        span_data = self._context_storage.top_span_data()
        assert span_data is not None

        span_data.update(
            output=output,
            usage=usage,
            model=model,
            provider=provider,
        )
        self._end_current_span()

    def before_tool_callback(
        self,
        tool: BaseTool,
        args: Dict[str, Any],
        tool_context: ToolContext,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        metadata = {"function_call_id": tool_context.function_call_id}

        if (current_span_data := self._context_storage.top_span_data()) is not None:
            self._attach_span_to_existing_span(
                current_span_data=current_span_data,
                name=tool.name,
                input=args,
                type="tool",
                metadata=metadata,
            )
        else:
            current_trace_data = self._context_storage.get_trace_data()
            assert current_trace_data is not None
            self._attach_span_to_existing_trace(
                current_trace_data=current_trace_data,
                name=tool.name,
                input=args,
                type="tool",
                metadata=metadata,
            )

    def after_tool_callback(
        self,
        tool: BaseTool,
        args: Dict[str, Any],
        tool_context: ToolContext,
        tool_response: Any,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        current_span_data = self._context_storage.top_span_data()
        assert current_span_data is not None
        if isinstance(tool_response, dict):
            current_span_data.update(output=tool_response)
        else:
            current_span_data.update(output={"output": tool_response})
        self._end_current_span()
