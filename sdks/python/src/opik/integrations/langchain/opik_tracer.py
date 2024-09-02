from typing import TYPE_CHECKING, Any, Dict, List, Optional, Literal, Set

from langchain_core.tracers import BaseTracer

from opik.api_objects import opik_client, span, trace
from opik import dict_utils
from opik import opik_context

from . import openai_run_helpers


if TYPE_CHECKING:
    from uuid import UUID

    from langchain_core.tracers.schemas import Run


def _get_span_type(run: "Run") -> Literal["llm", "tool", "general"]:
    if run.run_type in ["llm", "tool"]:
        return run.run_type  # type: ignore[no-any-return]

    return "general"


class OpikTracer(BaseTracer):
    """Opik Tracer."""

    def __init__(
        self,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> None:
        """
        Initialize the Opik Tracer.

        Args:
            tags: List of tags to be applied to each trace logged by the tracer.
            metadata: Additional metadata for each trace logged by the tracer.
        """
        super().__init__(**kwargs)
        self._trace_default_metadata = metadata if metadata is not None else {}
        self._trace_default_tags = tags

        self._span_map: Dict["UUID", span.Span] = {}
        """Map from run id to span."""

        self._created_traces_map: Dict["UUID", trace.Trace] = {}
        """Map from run id to trace."""

        self._externally_created_traces_ids: Set[str] = set()

        self._opik_client = opik_client.get_client_cached()

    def _persist_run(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()

        span_ = self._span_map[run.id]
        span_.end(output=run_dict["outputs"])

        if span_.trace_id not in self._externally_created_traces_ids:
            trace_ = self._created_traces_map[run.id]
            trace_.end(output=run_dict["outputs"])

    def _process_start_trace(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()
        if not run.parent_run_id:
            # This is the first run for the chain.
            self._track_root_run(run_dict)
        else:
            parent_span = self._span_map[run.parent_run_id]
            span_ = parent_span.span(
                input=run_dict["inputs"],
                metadata=run_dict["extra"],
                name=run.name,
                type=_get_span_type(run),
            )

            self._span_map[run.id] = span_

            if span_.trace_id not in self._externally_created_traces_ids:
                self._created_traces_map[run.id] = self._created_traces_map[
                    run.parent_run_id
                ]

    def _track_root_run(self, run_dict: Dict[str, Any]) -> None:
        run_metadata = run_dict["extra"].get("metadata", {})
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)
        current_span = opik_context.get_current_span()
        if current_span is not None:
            self._attach_span_to_existing_span(
                run_dict=run_dict,
                current_span=current_span,
                root_metadata=root_metadata,
            )
            return

        current_trace = opik_context.get_current_trace()
        if current_trace is not None:
            self._attach_span_to_existing_trace(
                run_dict=run_dict,
                current_trace=current_trace,
                root_metadata=root_metadata,
            )
            return

        self._initialize_span_and_trace_from_scratch(
            run_dict=run_dict, root_metadata=root_metadata
        )

    def _initialize_span_and_trace_from_scratch(
        self, run_dict: Dict[str, Any], root_metadata: Dict[str, Any]
    ) -> None:
        trace_ = self._opik_client.trace(
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
        )

        self._created_traces_map[run_dict["id"]] = trace_

        span_ = trace_.span(
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
        )

        self._span_map[run_dict["id"]] = span_

    def _attach_span_to_existing_span(
        self,
        run_dict: Dict[str, Any],
        current_span: span.Span,
        root_metadata: Dict[str, Any],
    ) -> None:
        span_ = current_span.span(
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
        )
        self._span_map[run_dict["id"]] = span_
        self._externally_created_traces_ids.add(span_.trace_id)

    def _attach_span_to_existing_trace(
        self,
        run_dict: Dict[str, Any],
        current_trace: trace.Trace,
        root_metadata: Dict[str, Any],
    ) -> None:
        span_ = current_trace.span(
            name=run_dict["name"],
            input=run_dict["inputs"],
            metadata=root_metadata,
            tags=self._trace_default_tags,
        )
        self._span_map[run_dict["id"]] = span_
        self._externally_created_traces_ids.add(current_trace.id)

    def _process_end_trace(self, run: "Run") -> None:
        run_dict: Dict[str, Any] = run.dict()
        if not run.parent_run_id:
            pass
            # Langchain will call _persist_run for us
        else:
            span = self._span_map[run.id]
            if openai_run_helpers.is_openai_run(run):
                usage = openai_run_helpers.try_get_token_usage(run_dict)
            else:
                usage = None

            span.end(output=run_dict["outputs"], usage=usage)

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
        result: List[trace.Trace] = []
        processed_ids = set()

        for trace_ in self._created_traces_map.values():
            if trace_.id not in processed_ids:
                result.append(trace_)
            processed_ids.add(trace_.id)

        return result

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
