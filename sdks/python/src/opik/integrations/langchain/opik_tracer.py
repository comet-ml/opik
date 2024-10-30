import logging
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Literal, Set

from langchain_core.tracers import BaseTracer

from opik.api_objects import opik_client, span, trace
from opik import dict_utils
from opik import opik_context

from . import openai_run_helpers, opik_encoder_extension
from ...api_objects import helpers

if TYPE_CHECKING:
    from uuid import UUID

    from langchain_core.tracers.schemas import Run

LOGGER = logging.getLogger(__name__)

opik_encoder_extension.register()


def _get_span_type(run: "Run") -> Literal["llm", "tool", "general"]:
    if run.run_type in ["llm", "tool"]:
        return run.run_type  # type: ignore[no-any-return]

    return "general"


class OpikTracer(BaseTracer):
    """Langchain Opik Tracer.

    Args:
        tags: List of tags to be applied to each trace logged by the tracer.
        metadata: Additional metadata for each trace logged by the tracer.
        project_name: The name of the project to log data.
    """

    def __init__(
        self,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        project_name: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self._trace_default_metadata = metadata if metadata is not None else {}
        self._trace_default_tags = tags

        self._span_data_map: Dict["UUID", span.SpanData] = {}
        """Map from run id to span data."""

        self._created_traces_data_map: Dict["UUID", trace.TraceData] = {}
        """Map from run id to trace data."""

        self._created_traces: List[trace.Trace] = []

        self._externally_created_traces_ids: Set[str] = set()

        self._project_name = project_name

        self._opik_client = opik_client.Opik(
            _use_batching=True, project_name=project_name
        )

    def _persist_run(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()

        span_data = self._span_data_map[run.id]
        span_data.init_end_time().update(output=run_dict["outputs"])
        self._opik_client.span(**span_data.__dict__)

        if span_data.trace_id not in self._externally_created_traces_ids:
            trace_data = self._created_traces_data_map[run.id]
            trace_data.init_end_time().update(output=run_dict["outputs"])
            trace_ = self._opik_client.trace(**trace_data.__dict__)
            self._created_traces.append(trace_)

    def _process_start_trace(self, run: "Run") -> None:
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
                type=_get_span_type(run),
                project_name=project_name,
            )

            self._span_data_map[run.id] = span_data

            if span_data.trace_id not in self._externally_created_traces_ids:
                self._created_traces_data_map[run.id] = self._created_traces_data_map[
                    run.parent_run_id
                ]

    def _track_root_run(self, run_dict: Dict[str, Any]) -> None:
        run_metadata = run_dict["extra"].get("metadata", {})
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)
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
        )
        self._span_data_map[run_dict["id"]] = span_data
        self._externally_created_traces_ids.add(current_trace_data.id)

    def _process_end_trace(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()
        if not run.parent_run_id:
            pass
            # Langchain will call _persist_run for us
        else:
            span_data = self._span_data_map[run.id]
            if openai_run_helpers.is_openai_run(run):
                usage = openai_run_helpers.try_get_token_usage(run_dict)
            else:
                usage = None

            span_data.init_end_time().update(
                output=run_dict["outputs"],
                usage=usage,
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

    def _on_llm_start(self, run: "Run") -> None:
        """Process the LLM Run upon start."""
        self._process_start_trace(run)

    def _on_llm_end(self, run: "Run") -> None:
        """Process the LLM Run."""
        self._process_end_trace(run)

    def _on_llm_error(self, run: "Run") -> None:
        """Process the LLM Run upon error."""
        self._process_end_trace(run)

    def _on_chain_start(self, run: "Run") -> None:
        """Process the Chain Run upon start."""
        self._process_start_trace(run)

    def _on_chain_end(self, run: "Run") -> None:
        """Process the Chain Run."""
        self._process_end_trace(run)

    def _on_chain_error(self, run: "Run") -> None:
        """Process the Chain Run upon error."""
        self._process_end_trace(run)

    def _on_tool_start(self, run: "Run") -> None:
        """Process the Tool Run upon start."""
        self._process_start_trace(run)

    def _on_tool_end(self, run: "Run") -> None:
        """Process the Tool Run."""
        self._process_end_trace(run)

    def _on_tool_error(self, run: "Run") -> None:
        """Process the Tool Run upon error."""
        self._process_end_trace(run)
