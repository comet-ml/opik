import logging
import datetime
from typing import Any, Dict, List, Literal, Optional, Set, TYPE_CHECKING, cast, Tuple
import contextvars
from langchain_core import language_models
from langchain_core.tracers import BaseTracer
from langchain_core.tracers.schemas import Run

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span, trace
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict
from opik.validation import parameters_validator
from . import (
    base_llm_patcher,
    opik_encoder_extension,
    provider_usage_extractors,
)

from ...api_objects import helpers, opik_client
import opik.context_storage as context_storage
import opik.decorator.tracing_runtime_config as tracing_runtime_config

if TYPE_CHECKING:
    from uuid import UUID

    from langchain_core.runnables.graph import Graph

    from langchain_core.messages import BaseMessage

LOGGER = logging.getLogger(__name__)

opik_encoder_extension.register()

language_models.BaseLLM.dict = base_llm_patcher.base_llm_dict_patched()

SpanType = Literal["llm", "tool", "general"]


def _get_span_type(run: Dict[str, Any]) -> SpanType:
    if run.get("run_type") in ["llm", "tool"]:
        return cast(SpanType, run.get("run_type"))

    if run.get("run_type") in ["prompt"]:
        return cast(SpanType, "tool")

    return cast(SpanType, "general")


class OpikTracer(BaseTracer):
    """Langchain Opik Tracer.

    Args:
        tags: List of tags to be applied to each trace logged by the tracer.
        metadata: Additional metadata for each trace logged by the tracer.
        graph: A LangGraph Graph object to track the Graph Definition in Opik.
        project_name: The name of the project to log data.
    """

    def __init__(
        self,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        graph: Optional["Graph"] = None,
        project_name: Optional[str] = None,
        distributed_headers: Optional[DistributedTraceHeadersDict] = None,
        thread_id: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
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
            self._trace_default_metadata["_opik_graph_definition"] = {
                "format": "mermaid",
                "data": graph.draw_mermaid(),
            }

        self._trace_default_tags = tags

        self._span_data_map: Dict["UUID", span.SpanData] = {}
        """Map from run id to span data."""

        self._created_traces_data_map: Dict["UUID", trace.TraceData] = {}
        """Map from run id to trace data."""

        self._created_traces: List[trace.Trace] = []

        self._externally_created_traces_ids: Set[str] = set()

        self._project_name = project_name

        self._distributed_headers = distributed_headers

        self._opik_client = opik_client.get_client_cached()

        self._thread_id = thread_id

        self._opik_context_storage = context_storage.get_current_context_instance()

        self._root_run_external_parent_span_id: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("root_run_external_parent_span_id", default=None)

    def _is_opik_span_created_by_this_tracer(self, span_id: str) -> bool:
        return any(span.id == span_id for span in self._span_data_map.values())

    def _is_opik_trace_created_by_this_tracer(self, trace_id: str) -> bool:
        return any(
            trace.id == trace_id for trace in self._created_traces_data_map.values()
        )

    def _persist_run(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()

        error_info: Optional[ErrorInfoDict]
        if run_dict["error"] is not None:
            output = None
            error_info = {
                "exception_type": "Exception",
                "traceback": run_dict["error"],
            }
        else:
            output = run_dict["outputs"]
            error_info = None

        span_data = self._span_data_map[run.id]

        if (
            span_data.parent_span_id is not None
            and self._is_opik_span_created_by_this_tracer(span_data.parent_span_id)
        ):
            # Langchain lost parent-child relationship for Run, so it calls _persist_run
            # for a subchain when the ACTUAL root run is not yet persisted.
            # However we know that the parent span was created by this tracer, so we don't
            # want to finalize the trace
            return

        self._ensure_no_hanging_opik_tracer_spans()

        if span_data.trace_id not in self._externally_created_traces_ids:
            trace_data = self._created_traces_data_map[run.id]

            # workaround for `.astream()` method usage
            if trace_data.input == {"input": ""}:
                trace_data.input = run_dict["inputs"]

            trace_data.init_end_time().update(output=output, error_info=error_info)
            trace_ = self._opik_client.trace(**trace_data.as_parameters)

            assert trace_ is not None
            self._created_traces.append(trace_)
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
        self, run_dict: Dict[str, Any]
    ) -> Tuple[Optional[trace.TraceData], span.SpanData]:
        run_metadata = run_dict["extra"].get("metadata", {})
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)
        self._update_thread_id_from_metadata(run_dict)

        if self._distributed_headers:
            new_span_data = self._attach_span_to_distributed_headers(
                run_dict=run_dict,
                root_metadata=root_metadata,
            )
            return None, new_span_data

        current_span_data = self._opik_context_storage.top_span_data()
        self._root_run_external_parent_span_id.set(
            current_span_data.id if current_span_data is not None else None
        )
        if current_span_data is not None:
            new_span_data = self._attach_span_to_existing_span(
                run_dict=run_dict,
                current_span_data=current_span_data,
                root_metadata=root_metadata,
            )
            return None, new_span_data

        current_trace_data = self._opik_context_storage.get_trace_data()
        if current_trace_data is not None:
            new_span_data = self._attach_span_to_existing_trace(
                run_dict=run_dict,
                current_trace_data=current_trace_data,
                root_metadata=root_metadata,
            )
            return None, new_span_data

        return self._initialize_span_and_trace_from_scratch(
            run_dict=run_dict, root_metadata=root_metadata
        )

    def _initialize_span_and_trace_from_scratch(
        self, run_dict: Dict[str, Any], root_metadata: Dict[str, Any]
    ) -> Tuple[trace.TraceData, span.SpanData]:
        trace_data = trace.TraceData(
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=self._project_name,
            thread_id=self._thread_id,
        )

        self._created_traces_data_map[run_dict["id"]] = trace_data

        span_data = span.SpanData(
            trace_id=trace_data.id,
            parent_span_id=None,
            name=run_dict["name"],
            input=run_dict["inputs"],
            type=_get_span_type(run_dict),
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=self._project_name,
        )

        self._span_data_map[run_dict["id"]] = span_data

        return trace_data, span_data

    def _attach_span_to_existing_span(
        self,
        run_dict: Dict[str, Any],
        current_span_data: span.SpanData,
        root_metadata: Dict[str, Any],
    ) -> span.SpanData:
        project_name = helpers.resolve_child_span_project_name(
            current_span_data.project_name,
            self._project_name,
        )

        span_data = span.SpanData(
            trace_id=current_span_data.trace_id,
            parent_span_id=current_span_data.id,
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=project_name,
            type=_get_span_type(run_dict),
        )
        self._span_data_map[run_dict["id"]] = span_data
        if not self._is_opik_trace_created_by_this_tracer(span_data.trace_id):
            self._externally_created_traces_ids.add(span_data.trace_id)

        return span_data

    def _attach_span_to_existing_trace(
        self,
        run_dict: Dict[str, Any],
        current_trace_data: trace.TraceData,
        root_metadata: Dict[str, Any],
    ) -> span.SpanData:
        project_name = helpers.resolve_child_span_project_name(
            current_trace_data.project_name,
            self._project_name,
        )

        span_data = span.SpanData(
            trace_id=current_trace_data.id,
            parent_span_id=None,
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=project_name,
            type=_get_span_type(run_dict),
        )
        self._span_data_map[run_dict["id"]] = span_data
        if not self._is_opik_trace_created_by_this_tracer(current_trace_data.id):
            self._externally_created_traces_ids.add(current_trace_data.id)
        return span_data

    def _attach_span_to_distributed_headers(
        self,
        run_dict: Dict[str, Any],
        root_metadata: Dict[str, Any],
    ) -> span.SpanData:
        if self._distributed_headers is None:
            raise ValueError("Distributed headers are not set")

        span_data = span.SpanData(
            trace_id=self._distributed_headers["opik_trace_id"],
            parent_span_id=self._distributed_headers["opik_parent_span_id"],
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=self._project_name,
            type=_get_span_type(run_dict),
        )
        self._span_data_map[run_dict["id"]] = span_data
        self._externally_created_traces_ids.add(span_data.trace_id)
        return span_data

    def _process_start_span(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()
        new_span_data: span.SpanData
        new_trace_data: Optional[trace.TraceData] = None

        if not run.parent_run_id:
            # This is the first run for the chain.
            new_trace_data, new_span_data = self._track_root_run(run_dict)
            if new_trace_data is not None:
                self._opik_context_storage.set_trace_data(new_trace_data)
                if (
                    self._opik_client.config.log_start_trace_span
                    and tracing_runtime_config.is_tracing_active()
                ):
                    self._opik_client.trace(**new_trace_data.as_start_parameters)

            self._opik_context_storage.add_span_data(new_span_data)
            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.span(**new_span_data.as_start_parameters)
            return

        parent_span_data = self._span_data_map[run.parent_run_id]

        project_name = helpers.resolve_child_span_project_name(
            parent_span_data.project_name,
            self._project_name,
        )

        new_span_data = span.SpanData(
            trace_id=parent_span_data.trace_id,
            parent_span_id=parent_span_data.id,
            input=run_dict["inputs"],
            metadata=run_dict["extra"],
            name=run.name,
            type=_get_span_type(run_dict),
            project_name=project_name,
        )
        new_span_data.update(metadata={"created_from": "langchain"})

        self._span_data_map[run.id] = new_span_data

        if new_span_data.trace_id not in self._externally_created_traces_ids:
            self._created_traces_data_map[run.id] = self._created_traces_data_map[
                run.parent_run_id
            ]

        self._opik_context_storage.add_span_data(new_span_data)
        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.span(**new_span_data.as_start_parameters)

    def _process_end_span(self, run: "Run") -> None:
        try:
            run_dict: Dict[str, Any] = run.dict()
            span_data = self._span_data_map[run.id]

            usage_info = provider_usage_extractors.try_extract_provider_usage_data(
                run_dict
            )
            if usage_info is None:
                usage_info = llm_usage.LLMUsageInfo()

            # workaround for `.astream()` method usage
            if span_data.input == {"input": ""}:
                span_data.input = run_dict["inputs"]

            span_data.init_end_time().update(
                output=run_dict["outputs"],
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
            self._opik_context_storage.trim_span_data_stack_to_certain_span(
                span_id=span_data.id
            )
            self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

    def _process_end_span_with_error(self, run: "Run") -> None:
        try:
            run_dict: Dict[str, Any] = run.dict()
            span_data = self._span_data_map[run.id]
            error_info: ErrorInfoDict = {
                "exception_type": "Exception",
                "traceback": run_dict["error"],
            }

            span_data.init_end_time().update(
                output=None,
                error_info=error_info,
            )
            if tracing_runtime_config.is_tracing_active():
                self._opik_client.span(**span_data.as_parameters)
        except Exception as e:
            LOGGER.debug(f"Failed during _process_end_span_with_error: {e}")
        finally:
            self._opik_context_storage.trim_span_data_stack_to_certain_span(
                span_id=span_data.id
            )
            self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

    def _update_thread_id_from_metadata(self, run_dict: Dict[str, Any]) -> None:
        if not self._thread_id:
            # We want to default to any manually set thread_id, so only update if self._thread_id is not already set
            thread_id = run_dict["extra"].get("metadata", {}).get("thread_id")

            if thread_id:
                self._thread_id = thread_id

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

    def _skip_tracking(self) -> bool:
        if not tracing_runtime_config.is_tracing_active():
            return True

        return False

    def _on_llm_start(self, run: "Run") -> None:
        """Process the LLM Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run)

    def on_chat_model_start(
        self,
        serialized: Dict[str, Any],
        messages: List[List["BaseMessage"]],
        *,
        run_id: "UUID",
        tags: Optional[List[str]] = None,
        parent_run_id: Optional["UUID"] = None,
        metadata: Optional[Dict[str, Any]] = None,
        name: Optional[str] = None,
        **kwargs: Any,
    ) -> "Run":
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

    def _on_chat_model_start(self, run: "Run") -> None:
        """Process the Chat Model Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run)

    def _on_llm_end(self, run: "Run") -> None:
        """Process the LLM Run."""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_llm_error(self, run: "Run") -> None:
        """Process the LLM Run upon error."""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)

    def _on_chain_start(self, run: "Run") -> None:
        """Process the Chain Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run)

    def _on_chain_end(self, run: "Run") -> None:
        """Process the Chain Run."""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_chain_error(self, run: "Run") -> None:
        """Process the Chain Run upon error."""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)

    def _on_tool_start(self, run: "Run") -> None:
        """Process the Tool Run upon start."""
        if self._skip_tracking():
            return

        self._process_start_span(run)

    def _on_tool_end(self, run: "Run") -> None:
        """Process the Tool Run."""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_tool_error(self, run: "Run") -> None:
        """Process the Tool Run upon error."""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)
