import logging
import datetime
from typing import (
    Any,
    Dict,
    List,
    Optional,
    TYPE_CHECKING,
    Callable,
    NamedTuple,
    Union,
)
import contextvars
from uuid import UUID

from langchain_core import language_models
from langchain_core.tracers import BaseTracer
from langchain_core.tracers.schemas import Run

import opik
from opik import context_storage, dict_utils, llm_usage, tracing_runtime_config
from opik.api_objects import span, trace
from opik.decorator import arguments_helpers, span_creation_handler
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict, LLMProvider
from opik.validation import parameters_validator
from . import (
    base_llm_patcher,
    run_parse_helpers,
    opik_encoder_extension,
    provider_usage_extractors,
    response_cost_extractors,
    run_state,
)

from ...api_objects import helpers

if TYPE_CHECKING:
    from langchain_core.runnables.graph import Graph
    from langchain_core.messages import BaseMessage

LOGGER = logging.getLogger(__name__)

opik_encoder_extension.register()

language_models.BaseLLM.dict = base_llm_patcher.base_llm_dict_patched()

# A callable that receives an error string and returns True if the error should be skipped,
# or False otherwise.
SkipErrorCallback = Callable[[str], bool]

# A fixed provider to record on an LLM span: a plain string or an LLMProvider.
ProviderOverride = Union[str, LLMProvider]


class ProviderResolverContext(NamedTuple):
    """What a provider-resolver callback receives for a single LLM run.

    ``model`` is the model name parsed from the run and is the usual routing key
    (e.g. return "bedrock" when it contains "anthropic"). ``run`` is the raw
    LangChain run dict, an escape hatch for routing on anything ``model`` alone
    can't express.
    """

    model: Optional[str]
    run: Dict[str, Any]


# A callable that receives a ProviderResolverContext and returns the provider to
# record for that specific run. Returning None falls back to the provider
# auto-detected from the run.
ProviderResolver = Callable[[ProviderResolverContext], Optional[ProviderOverride]]

# Placeholder output dictionary used when errors are intentionally skipped
# via the skip_error_callback. This signals that the output was not produced
# due to a handled/ignored error during execution.
ERROR_SKIPPED_OUTPUTS = {"warning": "Error output skipped by skip_error_callback."}

# Constants for LangGraph interrupt/resume functionality
LANGGRAPH_INTERRUPT_OUTPUT_KEY = "__interrupt__"
LANGGRAPH_RESUME_INPUT_KEY = "__resume__"
LANGGRAPH_INTERRUPT_METADATA_KEY = "_langgraph_interrupt"

# Constant for LangGraph ParentCommand (multi-agent control flow routing)
LANGGRAPH_PARENT_COMMAND_METADATA_KEY = "_langgraph_parent_command"


class TrackRootRunResult(NamedTuple):
    new_trace_data: Optional[trace.TraceData]
    new_span_data: Optional[span.SpanData]


class OpikTracer(BaseTracer):
    """Langchain Opik Tracer."""

    def __init__(
        self,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        graph: Optional["Graph"] = None,
        project_name: Optional[str] = None,
        distributed_headers: Optional[DistributedTraceHeadersDict] = None,
        thread_id: Optional[str] = None,
        skip_error_callback: Optional[SkipErrorCallback] = None,
        opik_context_read_only_mode: bool = False,
        provider: Optional[Union[ProviderOverride, ProviderResolver]] = None,
        **kwargs: Any,
    ) -> None:
        """
        Initializes an instance of the class with various parameters for traces, metadata, and project configuration.

        Args:
            tags: List of tags associated with logged traces.
            metadata: Dictionary containing metadata information for logged traces.
            graph: A LangGraph Graph object for representing dependencies or flow
                to track the Graph Definition in Opik.
            project_name: Name of the project associated with the traces.
            distributed_headers: Headers for distributed tracing context.
            thread_id: Unique identifier for the conversational thread
                to be associated with traces.
            skip_error_callback : Callback function to handle skip errors logic.
                Allows defining custom logic for handling errors that are intentionally skipped.
                Please note that in traces/spans where errors are intentionally skipped,
                the output will be replaced with `ERROR_SKIPPED_OUTPUTS`. You can provide
                the output manually using `opik_context.get_current_span_data().update(output=...)`.
            opik_context_read_only_mode: Whether to adding/popping spans/traces to/from the context storage.
                * If False (default), OpikTracer will add created spans/traces to the opik context, so if there is a @track-decorated
                  function called inside the LangChain runnable, it will be attached to it's parent span from LangChain automatically.
                * If True, OpikTracer will not modify the context storage and only create spans/traces from LangChain's Run objects.
                  This might be useful when the environment doesn't support proper context isolation for concurrent operations and you
                  want to avoid modifying the Opik context stack due to unsafety.
            provider: The provider to record on LLM spans, used by the backend for
                cost calculation. Useful when calls are routed through an
                OpenAI-compatible proxy (e.g. a LiteLLM gateway), where the provider
                would otherwise be auto-detected as the proxy's hostname and no cost
                could be computed. Accepts:
                * a string or ``opik.LLMProvider`` to apply to every LLM span (the
                  common single-provider case), or
                * a callable receiving a ``ProviderResolverContext`` (``.model`` is
                  the parsed model name, ``.run`` the raw run dict) and returning
                  the provider for that specific run (for chains/graphs that mix
                  providers). Returning None from the callable falls back to the
                  auto-detected provider for that run.
            **kwargs: Additional arguments passed to the parent class constructor.
        """
        validator = parameters_validator.create_validator(
            method_name="__init__", class_name=self.__class__.__name__
        )
        validator.add_str_parameter(thread_id, name="thread_id")
        validator.add_str_parameter(project_name, name="project_name")
        validator.add_dict_parameter(metadata, name="metadata")
        validator.add_list_parameter(tags, name="tags")
        if not validator.validate():
            validator.raise_validation_error()

        super().__init__(**kwargs)
        self._trace_default_metadata = metadata if metadata is not None else {}
        self._trace_default_metadata["created_from"] = "langchain"

        if graph:
            self.set_graph(graph)

        self._trace_default_tags = tags

        self._run_state = run_state.RunStateStore()

        self._created_traces: List[trace.Trace] = []

        self._project_name = project_name

        self._distributed_headers = distributed_headers

        self._thread_id = thread_id

        self._opik_context_storage = context_storage.get_current_context_instance()

        self._root_run_external_parent_span_id: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("root_run_external_parent_span_id", default=None)

        self._skip_error_callback = skip_error_callback

        self._opik_context_read_only_mode = opik_context_read_only_mode

        self._provider = provider

    @property
    def _opik_client(self) -> opik.Opik:
        return opik.get_global_client()

    def set_graph(self, graph: "Graph") -> None:
        """
        Set the LangGraph graph structure for visualization in Opik traces.

        This method extracts the graph structure and stores it in trace metadata,
        allowing the graph to be visualized in the Opik UI.

        Args:
            graph: A LangGraph Graph object (typically obtained via graph.get_graph(xray=True)).
        """
        self._trace_default_metadata["_opik_graph_definition"] = {
            "format": "mermaid",
            "data": graph.draw_mermaid(),
        }

    def _persist_run(self, run: Run) -> None:
        run_dict: Dict[str, Any] = run.dict()

        error_info: Optional[ErrorInfoDict]
        trace_additional_metadata: Dict[str, Any] = {}

        error_str = run_dict.get("error")
        outputs: Optional[Dict[str, Any]] = None
        error_info = None

        if error_str is not None:
            # GraphInterrupt is not an error - it's a normal control flow for LangGraph
            if interrupt_value := run_parse_helpers.parse_graph_interrupt_value(
                error_str
            ):
                outputs = {LANGGRAPH_INTERRUPT_OUTPUT_KEY: interrupt_value}
                trace_additional_metadata[LANGGRAPH_INTERRUPT_METADATA_KEY] = True
                # Don't set error_info - this is not an error
            # ParentCommand is not an error - it's multi-agent routing in LangGraph
            elif run_parse_helpers.is_langgraph_parent_command(error_str):
                trace_additional_metadata[LANGGRAPH_PARENT_COMMAND_METADATA_KEY] = True
                # Don't set error_info - this is not an error
            elif not self._should_skip_error(error_str):
                error_info = ErrorInfoDict(
                    exception_type="Exception",
                    traceback=error_str,
                )
            else:
                outputs = ERROR_SKIPPED_OUTPUTS
        elif (outputs := run_dict.get("outputs")) is not None:
            if isinstance(outputs, dict):
                outputs = run_parse_helpers.extract_command_update(outputs)

        if not self._opik_context_read_only_mode:
            self._ensure_no_hanging_opik_tracer_spans()

        span_data = self._run_state.get_span_data(run.id)
        if span_data is None or self._run_state.owns_trace(span_data.trace_id):
            self._finalize_trace(
                run_id=run.id,
                run_dict=run_dict,
                trace_additional_metadata=trace_additional_metadata,
                outputs=outputs,
                error_info=error_info,
            )

    def _finalize_trace(
        self,
        run_id: UUID,
        run_dict: Dict[str, Any],
        trace_additional_metadata: Optional[Dict[str, Any]],
        outputs: Optional[Dict[str, Any]],
        error_info: Optional[ErrorInfoDict],
    ) -> None:
        trace_data = self._run_state.get_trace_data(run_id)
        if trace_data is None:
            LOGGER.warning(
                f"Trace data for run '{run_id}' not found in the traces data map. Skipping processing of _finalize_trace."
            )
            return

        # workaround for `.astream()` method usage
        if trace_data.input == {"input": ""}:
            trace_data.input = run_dict["inputs"]
        elif isinstance(trace_data.input, dict) and "input" in trace_data.input:
            input_value = trace_data.input.get("input")
            if resume_value := run_parse_helpers.extract_resume_value_from_command(
                input_value
            ):
                trace_data.input = {LANGGRAPH_RESUME_INPUT_KEY: resume_value}

        # Check if any child span has a GraphInterrupt output and use it for trace output
        for span_data in self._run_state.spans_for_trace(trace_data.id):
            if (
                span_data.metadata is not None
                and span_data.metadata.get(LANGGRAPH_INTERRUPT_METADATA_KEY) is True
            ):
                # Use the interrupt output from the child span
                outputs = span_data.output
                # Also propagate the interrupt metadata to trace
                if trace_additional_metadata is None:
                    trace_additional_metadata = {}
                trace_additional_metadata[LANGGRAPH_INTERRUPT_METADATA_KEY] = True
                break

        if trace_additional_metadata:
            trace_data.update(metadata=trace_additional_metadata)

        trace_data.init_end_time().update(output=outputs, error_info=error_info)
        trace_ = self._opik_client.__internal_api__trace__(**trace_data.as_parameters)

        assert trace_ is not None
        self._created_traces.append(trace_)
        if not self._opik_context_read_only_mode:
            self._opik_context_storage.pop_trace_data(ensure_id=trace_data.id)

    def _ensure_no_hanging_opik_tracer_spans(self) -> None:
        root_run_external_parent_span_id = self._root_run_external_parent_span_id.get()
        there_were_no_external_spans_before_chain_invocation = (
            root_run_external_parent_span_id is None
        )

        if there_were_no_external_spans_before_chain_invocation:
            self._opik_context_storage.clear_spans()
        else:
            assert root_run_external_parent_span_id is not None
            self._opik_context_storage.trim_span_data_stack_to_certain_span(
                root_run_external_parent_span_id
            )

    def _track_root_run(
        self, run_dict: Dict[str, Any], allow_duplicating_root_span: bool
    ) -> TrackRootRunResult:
        run_metadata = run_parse_helpers.get_run_metadata(run_dict)
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)

        # Track the parent span ID for LangGraph cleanup later
        current_span_data = self._opik_context_storage.top_span_data()
        parent_span_id_when_langgraph_started = (
            current_span_data.id if current_span_data is not None else None
        )
        self._root_run_external_parent_span_id.set(
            parent_span_id_when_langgraph_started
        )
        detected_thread_id = run_metadata.get("thread_id")
        thread_id = self._thread_id or detected_thread_id

        start_span_arguments = arguments_helpers.StartSpanParameters(
            name=run_dict["name"],
            input=run_dict["inputs"],
            type=run_parse_helpers.get_span_type(run_dict),
            tags=self._trace_default_tags,
            metadata=root_metadata,
            project_name=context_storage.resolve_project_name(
                self._project_name, "OpikTracer"
            ),
            thread_id=thread_id,
        )

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_span_arguments,
            distributed_trace_headers=self._distributed_headers,
            opik_context_storage=self._opik_context_storage,
        )

        should_skip_root_span_creation = (
            span_creation_result.trace_data is not None
            and run_parse_helpers.is_root_run(run_dict)
            and not allow_duplicating_root_span
        )
        if should_skip_root_span_creation:
            return TrackRootRunResult(
                new_trace_data=span_creation_result.trace_data,
                new_span_data=None,
            )

        return TrackRootRunResult(
            new_trace_data=span_creation_result.trace_data,
            new_span_data=span_creation_result.span_data,
        )

    def _process_start_span(self, run: Run, allow_duplicating_root_span: bool) -> None:
        try:
            self._process_start_span_unsafe(run, allow_duplicating_root_span)
        except Exception as e:
            LOGGER.error("Failed during _process_start_span: %s", e, exc_info=True)

    def _process_start_span_unsafe(
        self, run: Run, allow_duplicating_root_span: bool
    ) -> None:
        run_dict: Dict[str, Any] = run.dict()

        if not run.parent_run_id:
            self._create_root_trace_and_span(
                run_id=run.id,
                run_dict=run_dict,
                allow_duplicating_root_span=allow_duplicating_root_span,
            )
            return

        # Check if the parent is a skipped LangGraph/LangChain root run.
        # If so, attach children directly to trace.
        # Otherwise, attach to the parent span.
        if self._run_state.is_skipped_langgraph_root(run.parent_run_id):
            self._attach_span_to_local_or_distributed_trace(
                run_id=run.id,
                parent_run_id=run.parent_run_id,
                run_dict=run_dict,
            )
        else:
            self._attach_span_to_parent_span(
                run_id=run.id, parent_run_id=run.parent_run_id, run_dict=run_dict
            )

    def _create_root_trace_and_span(
        self, run_id: UUID, run_dict: Dict[str, Any], allow_duplicating_root_span: bool
    ) -> None:
        """
        Creates a root trace and span for a given run and stores the relevant trace and span
        data in local storage for future reference.

        The new span is only created if no new trace is created, i.e., when attached to an existing span
        or distributed headers. If a new trace is created, the span is skipped and only the
        trace data is stored in local storage for future reference.
        """
        # This is the first run for the chain.
        root_run_result = self._track_root_run(run_dict, allow_duplicating_root_span)
        if root_run_result.new_trace_data is not None:
            if not self._opik_context_read_only_mode:
                self._opik_context_storage.set_trace_data(
                    root_run_result.new_trace_data
                )

            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.__internal_api__trace__(
                    **root_run_result.new_trace_data.as_start_parameters
                )

        # If this is a LangGraph/LangChain root run under fresh trace, skip creating the span
        if root_run_result.new_span_data is None:
            # Mark this run as skipped and store trace data for child runs
            self._run_state.mark_skipped_langgraph_root(run_id)

            # Store trace data if we created a new trace but skip span data
            if root_run_result.new_trace_data is not None:
                self._save_span_trace_data_to_local_maps(
                    run_id=run_id,
                    span_data=None,
                    trace_data=root_run_result.new_trace_data,
                )
        else:
            # save new span and trace data to local maps to be able to retrieve them later
            self._save_span_trace_data_to_local_maps(
                run_id=run_id,
                span_data=root_run_result.new_span_data,
                trace_data=root_run_result.new_trace_data,
            )

            if not self._opik_context_read_only_mode:
                self._opik_context_storage.add_span_data(root_run_result.new_span_data)

            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.__internal_api__span__(
                    **root_run_result.new_span_data.as_start_parameters
                )

    def _attach_span_to_parent_span(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        Attaches child span to a parent span and updates relevant context storage.

        This method is responsible for creating a new span data object associated with a
        run, linking it to the parent span data, and saving it to local and external maps.
        Additionally, it updates the context storage and logs the span if tracing is active.
        """
        parent_span_data = self._run_state.get_span_data(parent_run_id)
        assert parent_span_data is not None

        project_name = helpers.resolve_child_span_project_name(
            parent_span_data.project_name,
            context_storage.resolve_project_name(self._project_name, "OpikTracer"),
        )

        new_span_data = span.SpanData(
            trace_id=parent_span_data.trace_id,
            parent_span_id=parent_span_data.id,
            input=run_dict["inputs"],
            metadata=run_parse_helpers.get_run_metadata(run_dict),
            name=run_dict["name"],
            type=run_parse_helpers.get_span_type(run_dict),
            project_name=project_name,
        )
        new_span_data.update(metadata={"created_from": "langchain"})

        self._save_span_trace_data_to_local_maps(
            run_id=run_id,
            span_data=new_span_data,
            trace_data=None,
        )

        if self._run_state.owns_trace(new_span_data.trace_id):
            # Parent may be a stream-restart root run that exists only as a span
            # (not a skipped LangGraph root); the store falls back to a trace_id
            # lookup so the child still inherits the trace data.
            self._run_state.link_child_run_to_parent_trace(
                child_run_id=run_id,
                parent_run_id=parent_run_id,
                trace_id=new_span_data.trace_id,
            )

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__span__(
                **new_span_data.as_start_parameters
            )

    def _attach_span_to_local_or_distributed_trace(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        Attaches child span directly to a trace by checking trace data or distributed
        headers and creates new span data based on the provided run information.
        """
        # Check if we have trace data (new trace) or distributed headers
        parent_trace_data = self._run_state.get_trace_data(parent_run_id)
        if parent_trace_data is not None:
            # LangGraph created a new trace - attach children directly to trace
            trace_data = parent_trace_data
            project_name = helpers.resolve_child_span_project_name(
                trace_data.project_name,
                context_storage.resolve_project_name(self._project_name, "OpikTracer"),
            )

            new_span_data = span.SpanData(
                trace_id=trace_data.id,
                parent_span_id=None,  # Direct child of trace
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                name=run_dict["name"],
                type=run_parse_helpers.get_span_type(run_dict),
                project_name=project_name,
            )
            if self._run_state.owns_trace(new_span_data.trace_id):
                self._run_state.save_trace_data(run_id, trace_data)

        elif self._distributed_headers:
            # LangGraph with distributed headers - attach to distributed trace
            new_span_data = span.SpanData(
                trace_id=self._distributed_headers["opik_trace_id"],
                parent_span_id=self._distributed_headers["opik_parent_span_id"],
                name=run_dict["name"],
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                tags=self._trace_default_tags,
                project_name=context_storage.resolve_project_name(
                    self._project_name, "OpikTracer"
                ),
                type=run_parse_helpers.get_span_type(run_dict),
            )

        elif (
            current_trace_data := self._opik_context_storage.get_trace_data()
        ) is not None:
            # LangGraph attached to existing trace - attach children directly to trace
            project_name = helpers.resolve_child_span_project_name(
                current_trace_data.project_name,
                context_storage.resolve_project_name(self._project_name, "OpikTracer"),
            )

            new_span_data = span.SpanData(
                trace_id=current_trace_data.id,
                parent_span_id=None,
                name=run_dict["name"],
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                tags=self._trace_default_tags,
                project_name=project_name,
                type=run_parse_helpers.get_span_type(run_dict),
            )
        else:
            LOGGER.warning(
                f"Cannot find trace data or distributed headers for LangGraph child run '{run_id}'"
            )
            return

        new_span_data.update(metadata={"created_from": "langchain"})
        self._save_span_trace_data_to_local_maps(
            run_id=run_id,
            span_data=new_span_data,
            trace_data=None,
        )

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__span__(
                **new_span_data.as_start_parameters
            )

    def _process_end_span(self, run: Run) -> None:
        span_data = None
        try:
            # Skip processing if this is a skipped LangGraph root run
            if self._run_state.is_skipped_langgraph_root(run.id):
                return

            span_data = self._run_state.get_span_data(run.id)
            if span_data is None:
                LOGGER.warning(
                    f"Span data for run '{run.id}' not found in the span data map. Skipping processing of end span."
                )
                return
            run_dict: Dict[str, Any] = run.dict()

            usage_info = provider_usage_extractors.try_extract_provider_usage_data(
                run_dict
            )
            if usage_info is None:
                usage_info = llm_usage.LLMUsageInfo()

            provider_override = self._resolve_provider(run_dict, usage_info.model)
            if provider_override is not None:
                usage_info.provider = provider_override

            total_cost = response_cost_extractors.try_extract_response_cost(run_dict)

            # workaround for `.astream()` method usage
            if span_data.input == {"input": ""} or span_data.input == {"input": {}}:
                span_data.input = run_dict["inputs"]
            elif isinstance(span_data.input, dict):
                input_value = span_data.input.get("input")
                if resume_value := run_parse_helpers.extract_resume_value_from_command(
                    input_value
                ):
                    span_data.input = {LANGGRAPH_RESUME_INPUT_KEY: resume_value}

            run_dict_outputs = run_dict.get("outputs")
            span_output = (
                run_parse_helpers.extract_command_update(run_dict_outputs)
                if isinstance(run_dict_outputs, dict)
                else {"output": run_dict_outputs}
            )

            span_data.init_end_time().update(
                output=span_output,
                usage=(
                    usage_info.usage.provider_usage.model_dump()
                    if isinstance(usage_info.usage, llm_usage.OpikUsage)
                    else usage_info.usage
                ),
                provider=usage_info.provider,
                model=usage_info.model,
                total_cost=total_cost,
            )

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)
        except Exception as e:
            LOGGER.error(f"Failed during _process_end_span: {e}", exc_info=True)
        finally:
            if span_data is not None and not self._opik_context_read_only_mode:
                self._opik_context_storage.trim_span_data_stack_to_certain_span(
                    span_id=span_data.id
                )
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)
            # A root run's end handler is the last callback for its whole subtree
            # (LangChain calls it after _persist_run), so release the state here.
            if run.parent_run_id is None:
                self._run_state.release_run_tree(run)

    def _resolve_provider(
        self, run_dict: Dict[str, Any], model: Optional[str]
    ) -> Optional[str]:
        if self._provider is None:
            return None

        if callable(self._provider):
            context = ProviderResolverContext(model=model, run=run_dict)
            try:
                resolved: Optional[ProviderOverride] = self._provider(context)
            except Exception:
                # A user callback must never break trace logging: warn and fall
                # back to the auto-detected provider for this run.
                LOGGER.warning(
                    "The provider resolver callback raised an exception; falling "
                    "back to the auto-detected provider.",
                    exc_info=True,
                )
                return None
        else:
            resolved = self._provider

        if isinstance(resolved, LLMProvider):
            # Normalize to the plain string value so a bare enum member never
            # leaks into logs/spans as "LLMProvider.OPENAI".
            return resolved.value

        return resolved

    def _should_skip_error(self, error_str: str) -> bool:
        if self._skip_error_callback is None:
            return False

        return self._skip_error_callback(error_str)

    def _process_end_span_with_error(self, run: Run) -> None:
        span_data = None
        try:
            # Skip processing if this is a skipped LangGraph root run
            if self._run_state.is_skipped_langgraph_root(run.id):
                return

            span_data = self._run_state.get_span_data(run.id)
            if span_data is None:
                LOGGER.warning(
                    f"Span data for run '{run.id}' not found in the span data map. Skipping processing of _process_end_span_with_error."
                )
                return

            run_dict: Dict[str, Any] = run.dict()
            error_str = run_dict["error"]

            # GraphInterrupt is not an error - it's a normal control flow for LangGraph
            if interrupt_value := run_parse_helpers.parse_graph_interrupt_value(
                error_str
            ):
                span_data.init_end_time().update(
                    metadata={LANGGRAPH_INTERRUPT_METADATA_KEY: True},
                    output={LANGGRAPH_INTERRUPT_OUTPUT_KEY: interrupt_value},
                )
            # ParentCommand is not an error - it's multi-agent routing in LangGraph
            elif run_parse_helpers.is_langgraph_parent_command(error_str):
                span_data.init_end_time().update(
                    metadata={LANGGRAPH_PARENT_COMMAND_METADATA_KEY: True},
                )
            elif self._should_skip_error(error_str):
                span_data.init_end_time().update(output=ERROR_SKIPPED_OUTPUTS)
            else:
                error_info = ErrorInfoDict(
                    exception_type="Exception",
                    traceback=error_str,
                )
                span_data.init_end_time().update(
                    output=None,
                    error_info=error_info,
                )

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)
        except Exception as e:
            LOGGER.debug(f"Failed during _process_end_span_with_error: {e}")
        finally:
            if span_data is not None and not self._opik_context_read_only_mode:
                self._opik_context_storage.trim_span_data_stack_to_certain_span(
                    span_id=span_data.id
                )
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)
            # A root run's end handler is the last callback for its whole subtree
            # (LangChain calls it after _persist_run), so release the state here.
            if run.parent_run_id is None:
                self._run_state.release_run_tree(run)

    def _save_span_trace_data_to_local_maps(
        self,
        run_id: UUID,
        span_data: Optional[span.SpanData],
        trace_data: Optional[trace.TraceData],
    ) -> None:
        if span_data is not None:
            self._run_state.save_span_data(run_id, span_data)

        if trace_data is not None:
            self._run_state.save_trace_data(run_id, trace_data)

    def flush(self) -> None:
        """
        Flush to ensure all data is sent to the Opik server.
        """
        self._opik_client.flush()

    def created_traces(self) -> List[trace.Trace]:
        """
        Get a list of traces created by OpikTracer.

        Returns:
            List[Trace]: A list of traces.
        """
        return self._created_traces

    def get_current_span_data_for_run(self, run_id: UUID) -> Optional[span.SpanData]:
        return self._run_state.get_span_data(run_id)

    def _skip_tracking(self) -> bool:
        if not tracing_runtime_config.is_tracing_active():
            return True

        return False

    def _on_llm_start(self, run: Run) -> None:
        """Process the LLM Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=True)

    def on_chat_model_start(
        self,
        serialized: Dict[str, Any],
        messages: List[List["BaseMessage"]],
        *,
        run_id: UUID,
        tags: Optional[List[str]] = None,
        parent_run_id: Optional[UUID] = None,
        metadata: Optional[Dict[str, Any]] = None,
        name: Optional[str] = None,
        **kwargs: Any,
    ) -> Run:
        """Start a trace for an LLM run.

        Duplicated from Langchain tracer, it is disabled by default in all tracers, see https://github.com/langchain-ai/langchain/blob/fdda1aaea14b257845a19023e8af5e20140ec9fe/libs/core/langchain_core/callbacks/manager.py#L270-L289 and https://github.com/langchain-ai/langchain/blob/fdda1aaea14b257845a19023e8af5e20140ec9fe/libs/core/langchain_core/tracers/core.py#L168-L180

        Args:
            serialized: The serialized model.
            messages: The messages.
            run_id: The run ID.
            tags: The tags. Defaults to None.
            parent_run_id: The parent run ID. Defaults to None.
            metadata: The metadata. Defaults to None.
            name: The name. Defaults to None.
            kwargs: Additional keyword arguments.

        Returns:
            Run: The run.
        """
        start_time = datetime.datetime.now(datetime.timezone.utc)
        if metadata:
            kwargs.update({"metadata": metadata})

        # We switched from langchain dumpd to model_dump() as we don't need all the langchain stuff
        chat_model_run = Run(
            id=run_id,
            parent_run_id=parent_run_id,
            serialized=serialized,
            inputs={
                "messages": [[msg.model_dump() for msg in batch] for batch in messages]
            },
            extra=kwargs,
            events=[{"name": "start", "time": start_time}],
            start_time=start_time,
            run_type="llm",
            tags=tags,
            name=name,  # type: ignore[arg-type]
        )

        self._start_trace(chat_model_run)
        self._on_chat_model_start(chat_model_run)
        return chat_model_run

    def _on_chat_model_start(self, run: Run) -> None:
        """Process the Chat Model Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=True)

    def _on_llm_end(self, run: Run) -> None:
        """Process the LLM Run."""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_llm_error(self, run: Run) -> None:
        """Process the LLM Run upon error."""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)

    def _on_chain_start(self, run: Run) -> None:
        """Process the Chain Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=False)

    def _on_chain_end(self, run: Run) -> None:
        """Process the Chain Run."""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_chain_error(self, run: Run) -> None:
        """Process the Chain Run upon error."""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)

    def _on_tool_start(self, run: Run) -> None:
        """Process the Tool Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=True)

    def _on_tool_end(self, run: Run) -> None:
        """Process the Tool Run."""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_tool_error(self, run: Run) -> None:
        """Process the Tool Run upon error."""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)
