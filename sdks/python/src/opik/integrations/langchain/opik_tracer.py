import logging
from typing import Any, Dict, List, Literal, Optional, Set, TYPE_CHECKING, cast

from langchain_core import language_models
from langchain_core.tracers import BaseTracer

from opik import dict_utils, opik_context, llm_usage
from opik.api_objects import span, trace
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict
from . import (
    base_llm_patcher,
    google_run_helpers,
    openai_run_helpers,
    opik_encoder_extension,
)
from ...api_objects import helpers, opik_client

if TYPE_CHECKING:
    from uuid import UUID

    from langchain_core.tracers.schemas import Run
    from langchain_core.runnables.graph import Graph

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
        **kwargs: Any,
    ) -> None:
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

        if span_data.trace_id not in self._externally_created_traces_ids:
            trace_data = self._created_traces_data_map[run.id]

            # workaround for `.astream()` method usage
            if trace_data.input == {"input": ""}:
                trace_data.input = run_dict["inputs"]

            trace_data.init_end_time().update(output=output, error_info=error_info)
            trace_ = self._opik_client.trace(**trace_data.__dict__)
            self._created_traces.append(trace_)

    def _process_start_span(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()
        if not run.parent_run_id:
            # This is the first run for the chain.
            self._track_root_run(run_dict)
        else:
            parent_span_data = self._span_data_map[run.parent_run_id]

            project_name = helpers.resolve_child_span_project_name(
                parent_span_data.project_name,
                self._project_name,
            )

            span_data = span.SpanData(
                trace_id=parent_span_data.trace_id,
                parent_span_id=parent_span_data.id,
                input=run_dict["inputs"],
                metadata=run_dict["extra"],
                name=run.name,
                type=_get_span_type(run_dict),
                project_name=project_name,
            )
            span_data.update(metadata={"created_from": "langchain"})

            self._span_data_map[run.id] = span_data

            if span_data.trace_id not in self._externally_created_traces_ids:
                self._created_traces_data_map[run.id] = self._created_traces_data_map[
                    run.parent_run_id
                ]

    def _track_root_run(self, run_dict: Dict[str, Any]) -> None:
        run_metadata = run_dict["extra"].get("metadata", {})
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)

        if self._distributed_headers:
            self._attach_span_to_distributed_headers(
                run_dict=run_dict,
                root_metadata=root_metadata,
            )
            return

        current_span_data = opik_context.get_current_span_data()
        if current_span_data is not None:
            self._attach_span_to_existing_span(
                run_dict=run_dict,
                current_span_data=current_span_data,
                root_metadata=root_metadata,
            )
            return

        current_trace_data = opik_context.get_current_trace_data()
        if current_trace_data is not None:
            self._attach_span_to_existing_trace(
                run_dict=run_dict,
                current_trace_data=current_trace_data,
                root_metadata=root_metadata,
            )
            return

        self._initialize_span_and_trace_from_scratch(
            run_dict=run_dict, root_metadata=root_metadata
        )

    def _initialize_span_and_trace_from_scratch(
        self, run_dict: Dict[str, Any], root_metadata: Dict[str, Any]
    ) -> None:
        trace_data = trace.TraceData(
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=self._project_name,
        )

        self._created_traces_data_map[run_dict["id"]] = trace_data

        span_ = span.SpanData(
            trace_id=trace_data.id,
            parent_span_id=None,
            name=run_dict["name"],
            input=run_dict["inputs"],
            type=_get_span_type(run_dict),
            metadata=root_metadata,
            tags=self._trace_default_tags,
            project_name=self._project_name,
        )

        self._span_data_map[run_dict["id"]] = span_

    def _attach_span_to_existing_span(
        self,
        run_dict: Dict[str, Any],
        current_span_data: span.SpanData,
        root_metadata: Dict[str, Any],
    ) -> None:
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
        self._externally_created_traces_ids.add(span_data.trace_id)

    def _attach_span_to_existing_trace(
        self,
        run_dict: Dict[str, Any],
        current_trace_data: trace.TraceData,
        root_metadata: Dict[str, Any],
    ) -> None:
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
        self._externally_created_traces_ids.add(current_trace_data.id)

    def _attach_span_to_distributed_headers(
        self,
        run_dict: Dict[str, Any],
        root_metadata: Dict[str, Any],
    ) -> None:
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

    def _process_end_span(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()
        span_data = self._span_data_map[run.id]
        usage_info = llm_usage.LLMUsageInfo()

        if openai_run_helpers.is_openai_run(run):
            usage_info = openai_run_helpers.get_llm_usage_info(run_dict)
        elif google_run_helpers.is_google_run(run):
            usage_info = google_run_helpers.get_llm_usage_info(run_dict)

        # workaround for `.astream()` method usage
        if span_data.input == {"input": ""}:
            span_data.input = run_dict["inputs"]

        span_data.init_end_time().update(
            output=run_dict["outputs"],
            usage=usage_info.usage.provider_usage.model_dump()
            if isinstance(usage_info.usage, llm_usage.OpikUsage)
            else usage_info.usage,
            provider=usage_info.provider,
            model=usage_info.model,
        )

        self._opik_client.span(**span_data.__dict__)

    def _process_end_span_with_error(self, run: "Run") -> None:
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
        self._opik_client.span(**span_data.__dict__)

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
        config = self._opik_client.config
        if config.track_disable:
            return True

        return False

    def _on_llm_start(self, run: "Run") -> None:
        """Process the LLM Run upon start."""
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
