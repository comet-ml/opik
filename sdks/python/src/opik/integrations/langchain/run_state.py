from typing import Dict, Iterable, List, Optional, Set, TYPE_CHECKING
from uuid import UUID

from opik.api_objects import span, trace

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run


class RunStateStore:
    """Per-run bookkeeping for :class:`OpikTracer`.

    Owns the mapping from LangChain run ids to the span/trace data the tracer
    created for them. Trace ownership - whether the tracer must finalize a trace
    or leave it to whoever created it - is derived from that same trace data map
    (see :meth:`owns_trace`) rather than tracked separately.

    All of this state is scoped to in-flight runs. :meth:`release_run_tree`
    drops every entry belonging to a finished root run's subtree, so a
    long-lived tracer reused across invocations - the documented "build once,
    pass on every invoke" pattern - stays flat in memory instead of growing for
    the process lifetime and pinning the retained prompt/completion payloads.
    """

    def __init__(self) -> None:
        self._span_data_by_run_id: Dict[UUID, span.SpanData] = {}
        self._trace_data_by_run_id: Dict[UUID, trace.TraceData] = {}
        self._skipped_langgraph_root_run_ids: Set[UUID] = set()

    def save_span_data(self, run_id: UUID, span_data: span.SpanData) -> None:
        self._span_data_by_run_id[run_id] = span_data

    def save_trace_data(self, run_id: UUID, trace_data: trace.TraceData) -> None:
        self._trace_data_by_run_id[run_id] = trace_data

    def get_span_data(self, run_id: UUID) -> Optional[span.SpanData]:
        return self._span_data_by_run_id.get(run_id)

    def get_trace_data(self, run_id: UUID) -> Optional[trace.TraceData]:
        return self._trace_data_by_run_id.get(run_id)

    def spans_for_trace(self, trace_id: str) -> Iterable[span.SpanData]:
        return [
            span_data
            for span_data in self._span_data_by_run_id.values()
            if span_data.trace_id == trace_id
        ]

    def link_child_run_to_parent_trace(
        self, child_run_id: UUID, parent_run_id: UUID, trace_id: str
    ) -> None:
        """Point a child run at its parent run's trace data.

        Falls back to a lookup by ``trace_id`` when the parent run has no trace
        data of its own (e.g. a stream-restart root that only exists as a span),
        leaving the child unlinked when neither is found.
        """
        trace_data = self._trace_data_by_run_id.get(parent_run_id)
        if trace_data is None:
            trace_data = self._find_trace_data_by_trace_id(trace_id)

        if trace_data is not None:
            self._trace_data_by_run_id[child_run_id] = trace_data

    def _find_trace_data_by_trace_id(self, trace_id: str) -> Optional[trace.TraceData]:
        for trace_data in self._trace_data_by_run_id.values():
            if trace_data.id == trace_id:
                return trace_data
        return None

    def mark_skipped_langgraph_root(self, run_id: UUID) -> None:
        self._skipped_langgraph_root_run_ids.add(run_id)

    def is_skipped_langgraph_root(self, run_id: UUID) -> bool:
        return run_id in self._skipped_langgraph_root_run_ids

    def owns_trace(self, trace_id: str) -> bool:
        """Whether this tracer created the trace (and so must finalize it).

        A trace the tracer did not create - an ambient ``@track`` trace, a
        distributed-header trace, a caller-provided one - is never in the trace
        data map, so this returns False and the tracer leaves it alone.
        """
        return any(
            trace_data.id == trace_id
            for trace_data in self._trace_data_by_run_id.values()
        )

    def is_empty(self) -> bool:
        return not (
            self._span_data_by_run_id
            or self._trace_data_by_run_id
            or self._skipped_langgraph_root_run_ids
        )

    def release_run_tree(self, root_run: "Run") -> None:
        """Drop all state for a finished root run and its whole subtree.

        LangChain attaches the entire subtree to the root run, so walking it
        yields every run id this store created bookkeeping for.
        """
        pending_runs: List["Run"] = [root_run]
        while pending_runs:
            current_run = pending_runs.pop()
            pending_runs.extend(current_run.child_runs)
            run_id = current_run.id

            self._span_data_by_run_id.pop(run_id, None)
            self._trace_data_by_run_id.pop(run_id, None)
            self._skipped_langgraph_root_run_ids.discard(run_id)
