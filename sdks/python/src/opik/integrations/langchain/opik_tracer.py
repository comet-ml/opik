import logging
import datetime
from typing import (
    Any,
    Dict,
    List,
    Literal,
    Optional,
    Set,
    TYPE_CHECKING,
    cast,
    Callable,
    NamedTuple,
)
import contextvars
from uuid import UUID

from langchain_core import language_models
from langchain_core.tracers import BaseTracer
from langchain_core.tracers.schemas import Run

from opik import context_storage, dict_utils, llm_usage, tracing_runtime_config
from opik.api_objects import span, trace
from opik.decorator import arguments_helpers, span_creation_handler
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict
from opik.validation import parameters_validator
from . import (
    base_llm_patcher,
    helpers as langchain_helpers,
    opik_encoder_extension,
    provider_usage_extractors,
)

from ...api_objects import helpers, opik_client

if TYPE_CHECKING:
    from langchain_core.runnables.graph import Graph
    from langchain_core.messages import BaseMessage

LOGGER = logging.getLogger(__name__)

opik_encoder_extension.register()

language_models.BaseLLM.dict = base_llm_patcher.base_llm_dict_patched()

SpanType = Literal["llm", "tool", "general"]

# A callable that receives an error string and returns True if the error should be skipped,
# or False otherwise.
SkipErrorCallback = Callable[[str], bool]

# Placeholder output dictionary used when errors are intentionally skipped
# via the skip_error_callback. This signals that the output was not produced
# due to a handled/ignored error during execution.
ERROR_SKIPPED_OUTPUTS = {"warning": "Error output skipped by skip_error_callback."}


class TrackRootRunResult(NamedTuple):
    new_trace_data: Optional[trace.TraceData]
    new_span_data: Optional[span.SpanData]


def _get_span_type(run: Dict[str, Any]) -> SpanType:
    if run.get("run_type") in ["llm", "tool"]:
        return cast(SpanType, run.get("run_type"))

    if run.get("run_type") in ["prompt"]:
        return cast(SpanType, "tool")

    return cast(SpanType, "general")


def _is_root_run(run_dict: Dict[str, Any]) -> bool:
    return run_dict.get("parent_run_id") is None


def _get_run_metadata(run_dict: Dict[str, Any]) -> Dict[str, Any]:
    return run_dict["extra"].get("metadata", {})


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

        self._span_data_map: Dict[UUID, span.SpanData] = {}
        """Map from run id to span data."""

        self._created_traces_data_map: Dict[UUID, trace.TraceData] = {}
        """Map from run id to trace data."""

        self._created_traces: List[trace.Trace] = []

        self._externally_created_traces_ids: Set[str] = set()

        self._skipped_langgraph_root_run_ids: Set[UUID] = set()
        """Set of run IDs for LangGraph root runs where we skip creating the span."""

        self._langgraph_parent_span_ids: Dict[UUID, Optional[str]] = {}
        """Map from LangGraph root run ID to parent span ID (None if attached to trace)."""

        self._project_name = project_name

        self._distributed_headers = distributed_headers

        self._opik_client = opik_client.get_client_cached()

        self._thread_id = thread_id

        self._opik_context_storage = context_storage.get_current_context_instance()

        self._root_run_external_parent_span_id: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("root_run_external_parent_span_id", default=None)

        self._skip_error_callback = skip_error_callback

        self._opik_context_read_only_mode = opik_context_read_only_mode

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

    def _is_opik_span_created_by_this_tracer(self, span_id: str) -> bool:
        return any(span_.id == span_id for span_ in self._span_data_map.values())

    def _is_opik_trace_created_by_this_tracer(self, trace_id: str) -> bool:
        return any(
            trace_.id == trace_id for trace_ in self._created_traces_data_map.values()
        )

    def _persist_run(self, run: Run) -> None:
        run_dict: Dict[str, Any] = run.dict()

        error_info: Optional[ErrorInfoDict]
        trace_additional_metadata: Dict[str, Any] = {}

        error_str = run_dict.get("error")
        outputs = None
        error_info = None

        if error_str is not None:
            if not self._should_skip_error(error_str):
                error_info = ErrorInfoDict(
                    exception_type="Exception",
                    traceback=error_str,
                )
            else:
                outputs = ERROR_SKIPPED_OUTPUTS
        elif (outputs := run_dict.get("outputs")) is not None:
            outputs, trace_additional_metadata = (
                langchain_helpers.split_big_langgraph_outputs(outputs)
            )

        if not self._opik_context_read_only_mode:
            self._ensure_no_hanging_opik_tracer_spans()

        span_data = self._span_data_map.get(run.id)
        if (
            span_data is None
            or span_data.trace_id not in self._externally_created_traces_ids
        ):
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
        trace_data = self._created_traces_data_map.get(run_id)
        if trace_data is None:
            LOGGER.warning(
                f"Trace data for run '{run_id}' not found in the traces data map. Skipping processing of _finalize_trace."
            )
            return

        # workaround for `.astream()` method usage
        if trace_data.input == {"input": ""}:
            trace_data.input = run_dict["inputs"]

        if trace_additional_metadata:
            trace_data.update(metadata=trace_additional_metadata)

        trace_data.init_end_time().update(output=outputs, error_info=error_info)
        trace_ = self._opik_client.trace(**trace_data.as_parameters)

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
        run_metadata = _get_run_metadata(run_dict)
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)
        self._update_thread_id_from_metadata(run_dict)

        # Track the parent span ID for LangGraph cleanup later
        current_span_data = self._opik_context_storage.top_span_data()
        parent_span_id_when_langgraph_started = (
            current_span_data.id if current_span_data is not None else None
        )
        self._root_run_external_parent_span_id.set(
            parent_span_id_when_langgraph_started
        )

        start_span_arguments = arguments_helpers.StartSpanParameters(
            name=run_dict["name"],
            input=run_dict["inputs"],
            type=_get_span_type(run_dict),
            tags=self._trace_default_tags,
            metadata=root_metadata,
            project_name=self._project_name,
            thread_id=self._thread_id,
        )

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_span_arguments,
            distributed_trace_headers=self._distributed_headers,
            opik_context_storage=self._opik_context_storage,
        )

        trace_created_externally = (
            span_creation_result.trace_data is None
            and not self._is_opik_trace_created_by_this_tracer(
                span_creation_result.span_data.trace_id
            )
        )
        if trace_created_externally:
            self._externally_created_traces_ids.add(
                span_creation_result.span_data.trace_id
            )

        should_skip_root_span_creation = (
            span_creation_result.trace_data is not None
            and _is_root_run(run_dict)
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
        if run.parent_run_id in self._skipped_langgraph_root_run_ids:
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
                self._opik_client.trace(
                    **root_run_result.new_trace_data.as_start_parameters
                )

        # If this is a LangGraph/LangChain root run under fresh trace, skip creating the span
        if root_run_result.new_span_data is None:
            # Mark this run as skipped and store trace data for child runs
            self._skipped_langgraph_root_run_ids.add(run_id)

            # Store parent span ID if LangGraph was attached to the existing span
            parent_span_id = self._root_run_external_parent_span_id.get()
            self._langgraph_parent_span_ids[run_id] = parent_span_id

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
                self._opik_client.span(
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
        parent_span_data = self._span_data_map[parent_run_id]

        project_name = helpers.resolve_child_span_project_name(
            parent_span_data.project_name,
            self._project_name,
        )

        new_span_data = span.SpanData(
            trace_id=parent_span_data.trace_id,
            parent_span_id=parent_span_data.id,
            input=run_dict["inputs"],
            metadata=_get_run_metadata(run_dict),
            name=run_dict["name"],
            type=_get_span_type(run_dict),
            project_name=project_name,
        )
        new_span_data.update(metadata={"created_from": "langchain"})

        self._save_span_trace_data_to_local_maps(
            run_id=run_id,
            span_data=new_span_data,
            trace_data=None,
        )

        if new_span_data.trace_id not in self._externally_created_traces_ids:
            self._created_traces_data_map[run_id] = self._created_traces_data_map[
                parent_run_id
            ]

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.span(**new_span_data.as_start_parameters)

    def _attach_span_to_local_or_distributed_trace(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        Attaches child span directly to a trace by checking trace data or distributed
        headers and creates new span data based on the provided run information.
        """
        # Check if we have trace data (new trace) or distributed headers
        if parent_run_id in self._created_traces_data_map:
            # LangGraph created a new trace - attach children directly to trace
            trace_data = self._created_traces_data_map[parent_run_id]
            project_name = helpers.resolve_child_span_project_name(
                trace_data.project_name,
                self._project_name,
            )

            new_span_data = span.SpanData(
                trace_id=trace_data.id,
                parent_span_id=None,  # Direct child of trace
                input=run_dict["inputs"],
                metadata=_get_run_metadata(run_dict),
                name=run_dict["name"],
                type=_get_span_type(run_dict),
                project_name=project_name,
            )
            if new_span_data.trace_id not in self._externally_created_traces_ids:
                self._created_traces_data_map[run_id] = trace_data

        elif self._distributed_headers:
            # LangGraph with distributed headers - attach to distributed trace
            new_span_data = span.SpanData(
                trace_id=self._distributed_headers["opik_trace_id"],
                parent_span_id=self._distributed_headers["opik_parent_span_id"],
                name=run_dict["name"],
                input=run_dict["inputs"],
                metadata=_get_run_metadata(run_dict),
                tags=self._trace_default_tags,
                project_name=self._project_name,
                type=_get_span_type(run_dict),
            )
            self._externally_created_traces_ids.add(new_span_data.trace_id)

        elif (
            current_trace_data := self._opik_context_storage.get_trace_data()
        ) is not None:
            # LangGraph attached to existing trace - attach children directly to trace
            project_name = helpers.resolve_child_span_project_name(
                current_trace_data.project_name,
                self._project_name,
            )

            new_span_data = span.SpanData(
                trace_id=current_trace_data.id,
                parent_span_id=None,
                name=run_dict["name"],
                input=run_dict["inputs"],
                metadata=_get_run_metadata(run_dict),
                tags=self._trace_default_tags,
                project_name=project_name,
                type=_get_span_type(run_dict),
            )

            if not self._is_opik_trace_created_by_this_tracer(current_trace_data.id):
                self._externally_created_traces_ids.add(current_trace_data.id)
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
            self._opik_client.span(**new_span_data.as_start_parameters)

    def _process_end_span(self, run: Run) -> None:
        span_data = None
        try:
            # Skip processing if this is a skipped LangGraph root run
            if run.id in self._skipped_langgraph_root_run_ids:
                return

            if run.id not in self._span_data_map:
                LOGGER.warning(
                    f"Span data for run '{run.id}' not found in the span data map. Skipping processing of end span."
                )
                return
            span_data = self._span_data_map[run.id]
            run_dict: Dict[str, Any] = run.dict()

            usage_info = provider_usage_extractors.try_extract_provider_usage_data(
                run_dict
            )
            if usage_info is None:
                usage_info = llm_usage.LLMUsageInfo()

            # workaround for `.astream()` method usage
            if span_data.input == {"input": ""}:
                span_data.input = run_dict["inputs"]

            filtered_output, additional_metadata = (
                langchain_helpers.split_big_langgraph_outputs(run_dict["outputs"])
            )

            if additional_metadata:
                span_data.update(metadata=additional_metadata)

            span_data.init_end_time().update(
                output=filtered_output,
                usage=(
                    usage_info.usage.provider_usage.model_dump()
                    if isinstance(usage_info.usage, llm_usage.OpikUsage)
                    else usage_info.usage
                ),
                provider=usage_info.provider,
                model=usage_info.model,
            )

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.span(**span_data.as_parameters)
        except Exception as e:
            LOGGER.error(f"Failed during _process_end_span: {e}", exc_info=True)
        finally:
            if span_data is not None and not self._opik_context_read_only_mode:
                self._opik_context_storage.trim_span_data_stack_to_certain_span(
                    span_id=span_data.id
                )
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

    def _should_skip_error(self, error_str: str) -> bool:
        if self._skip_error_callback is None:
            return False

        return self._skip_error_callback(error_str)

    def _process_end_span_with_error(self, run: Run) -> None:
        # Skip processing if this is a skipped LangGraph root run
        if run.id in self._skipped_langgraph_root_run_ids:
            return

        if run.id not in self._span_data_map:
            LOGGER.warning(
                f"Span data for run '{run.id}' not found in the span data map. Skipping processing of _process_end_span_with_error."
            )
            return

        span_data = None
        try:
            run_dict: Dict[str, Any] = run.dict()
            span_data = self._span_data_map[run.id]
            error_str = run_dict["error"]

            if self._should_skip_error(error_str):
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
                self._opik_client.span(**span_data.as_parameters)
        except Exception as e:
            LOGGER.debug(f"Failed during _process_end_span_with_error: {e}")
        finally:
            if span_data is not None and not self._opik_context_read_only_mode:
                self._opik_context_storage.trim_span_data_stack_to_certain_span(
                    span_id=span_data.id
                )
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

    def _update_thread_id_from_metadata(self, run_dict: Dict[str, Any]) -> None:
        if not self._thread_id:
            # We want to default to any manually set thread_id, so only update if self._thread_id is not already set
            thread_id = _get_run_metadata(run_dict).get("thread_id")

            if thread_id:
                self._thread_id = thread_id

    def _save_span_trace_data_to_local_maps(
        self,
        run_id: UUID,
        span_data: Optional[span.SpanData],
        trace_data: Optional[trace.TraceData],
    ) -> None:
        if span_data is not None:
            self._span_data_map[run_id] = span_data

        if trace_data is not None:
            self._created_traces_data_map[run_id] = trace_data

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
        return self._span_data_map.get(run_id)

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
