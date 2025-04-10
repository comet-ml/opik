import uuid
from typing import Any, Dict, List, Optional

from google.adk.agents.callback_context import CallbackContext
from google.adk.models import LlmRequest, LlmResponse
from google.adk.tools.base_tool import BaseTool
from google.adk.tools.tool_context import ToolContext
from opik import context_storage, datetime_helpers
from opik.api_objects import helpers, opik_client, trace
from opik.decorator import arguments_helpers
from opik.types import DistributedTraceHeadersDict

from .decorators import (
    ADKLLMTrackDecorator,
    ADKToolTrackDecorator,
    convert_adk_base_models,
)


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
        self.trace_data: Optional[trace.TraceData] = None
        self._client = opik_client.get_client_cached()

        self._last_model_output: Optional[Dict[str, Any]] = None

        self._llm_tracer = ADKLLMTrackDecorator()
        self._tool_tracer = ADKToolTrackDecorator()

    def before_agent_callback(
        self, callback_context: CallbackContext, *args: Any, **kwargs: Any
    ) -> None:
        if "opik_thread_id" in callback_context.state:
            thread_id = callback_context.state["opik_thread_id"]
        else:
            thread_id = str(uuid.uuid4())
            callback_context.state["opik_thread_id"] = thread_id

        # Should we create a trace here as we don't have an input?

        trace_metadata = self.metadata.copy()
        trace_metadata["adk_invocation_id"] = callback_context.invocation_id

        user_input = convert_adk_base_models(callback_context.user_content)

        self.trace_data = trace.TraceData(
            id=helpers.generate_id(),
            start_time=datetime_helpers.local_timestamp(),
            name=self.name or callback_context.agent_name,
            input=user_input,
            metadata=trace_metadata,
            tags=self.tags,
            project_name=self.project_name,
            thread_id=thread_id,
        )

        context_storage.set_trace_data(self.trace_data)

    def after_agent_callback(
        self, callback_context: CallbackContext, *args: Any, **kwargs: Any
    ) -> None:
        assert context_storage.get_trace_data() is self.trace_data
        assert self.trace_data is not None

        context_storage.pop_trace_data()

        output = self._last_model_output

        self.trace_data.update(output=output).init_end_time()

        self._client.trace(**self.trace_data.__dict__)

        # Cleaning
        self.trace_data = None
        self._last_model_output = None

    def before_model_callback(
        self,
        callback_context: CallbackContext,
        llm_request: LlmRequest,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        self._llm_tracer._before_call(
            func=self.before_model_callback,
            track_options=arguments_helpers.TrackOptions(
                name=llm_request.model,
                type="llm",
                capture_input=True,
                capture_output=True,
                flush=False,
                tags=[],
                metadata={},
                ignore_arguments=None,
                generations_aggregator=None,
                project_name=self.project_name,
            ),
            args=(llm_request,),
            kwargs=kwargs,
        )

    def after_model_callback(
        self,
        callback_context: CallbackContext,
        llm_response: LlmResponse,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        self._last_model_output = convert_adk_base_models(llm_response)

        self._llm_tracer._after_call(
            output=llm_response,
            error_info=None,
            capture_output=True,
            generators_span_to_end=None,
            generators_trace_to_end=None,
            flush=False,
        )

    def before_tool_callback(
        self,
        tool: BaseTool,
        args: Dict[str, Any],
        tool_context: ToolContext,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        metadata = {"function_call_id": tool_context.function_call_id}

        self._tool_tracer._before_call(
            func=self.before_model_callback,
            track_options=arguments_helpers.TrackOptions(
                name=tool.name,
                type="tool",
                capture_input=True,
                capture_output=True,
                flush=False,
                tags=[],
                metadata=metadata,
                ignore_arguments=None,
                generations_aggregator=None,
                project_name=self.project_name,
            ),
            args=(args,),
            kwargs=kwargs,
        )

    def after_tool_callback(
        self,
        tool: BaseTool,
        args: Dict[str, Any],
        tool_context: ToolContext,
        tool_response: Dict,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        self._tool_tracer._after_call(
            output=tool_response,
            error_info=None,
            capture_output=True,
            generators_span_to_end=None,
            generators_trace_to_end=None,
            flush=False,
        )
