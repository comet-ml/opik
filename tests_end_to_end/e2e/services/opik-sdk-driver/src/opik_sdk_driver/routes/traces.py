import atexit

import opik
from fastapi import APIRouter, Header, HTTPException

from ..opik_factory import make_opik_client
from ..schemas import (
    NestedTraceCreate,
    NestedTraceResponse,
    TraceCreate,
    TraceResponse,
)

router = APIRouter(prefix="/traces", tags=["traces"])


@router.post("", response_model=TraceResponse, status_code=201)
def create_trace(
    body: TraceCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> TraceResponse:
    # Use @opik.track to exercise the same code path users invoke; the
    # decorator emits a trace via the SDK's streamer thread. Bind the
    # global client to this request's auth context so flush_tracker and
    # search_traces share it. ApiError from the SDK is translated to
    # HTTP by the app-wide exception handler.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    @opik.track(name=body.name, project_name=body.project_name)
    def _emit(_input_value: str) -> str:
        return body.output

    try:
        _emit(body.input)
        opik.flush_tracker()
        traces = client.search_traces(
            project_name=body.project_name,
            filter_string=f'name = "{body.name}"',
            max_results=1,
            wait_for_at_least=1,
        )
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    if not traces:
        raise HTTPException(
            status_code=500,
            detail=f"Trace {body.name} not visible after create + flush",
        )

    trace = traces[0]
    return TraceResponse(
        id=str(trace.id),
        name=trace.name,
        project_id=str(trace.project_id),
    )


@router.post("/nested", response_model=NestedTraceResponse, status_code=201)
def create_nested_trace(
    body: NestedTraceCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> NestedTraceResponse:
    # Build a multi-span trace explicitly through the public SDK (client.trace
    # + trace.span/span.span), rather than via @opik.track which only emits a
    # single root span. Spans are ordered parent-before-child, so a span's
    # parent_index always points at an already-created span. ApiError from the
    # SDK is translated to HTTP by the app-wide exception handler.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        trace = client.trace(
            project_name=body.project_name,
            name=body.name,
            input=body.input,
            output=body.output,
            metadata=body.metadata,
            tags=body.tags,
            thread_id=body.thread_id,
            feedback_scores=body.feedback_scores,
        )

        created: list = []
        for index, span_seed in enumerate(body.spans):
            if span_seed.parent_index is None:
                parent = trace
            else:
                if not (0 <= span_seed.parent_index < index):
                    raise HTTPException(
                        status_code=422,
                        detail=(
                            f"span[{index}] parent_index {span_seed.parent_index} "
                            "must reference an earlier span (0 <= parent_index < index)"
                        ),
                    )
                parent = created[span_seed.parent_index]
            # The create call carries the full span payload. We deliberately do
            # NOT call span.end()/trace.end() afterwards: with batching enabled
            # an immediate end() update races the create in the same batch and
            # clobbers the rich fields (name/input/output/usage/model/cost) with
            # nulls. See the SDK note on batching-and-updates.
            span = parent.span(
                name=span_seed.name,
                type=span_seed.type,
                input=span_seed.input,
                output=span_seed.output,
                metadata=span_seed.metadata,
                model=span_seed.model,
                provider=span_seed.provider,
                usage=span_seed.usage,
                total_cost=span_seed.total_cost,
            )
            created.append(span)

        opik.flush_tracker()
        # Confirm visibility by the trace's own id (unique), not by name — trace
        # names are not unique within a project, so a name filter could return a
        # different, older trace and make the response point at the wrong spans.
        traces = client.search_traces(
            project_name=body.project_name,
            filter_string=f'id = "{trace.id}"',
            max_results=1,
            wait_for_at_least=1,
        )
        if not traces:
            raise HTTPException(
                status_code=500,
                detail=f"Trace {trace.id} not visible after create + flush",
            )
        found = traces[0]
        # Block until every span is queryable, not just the trace. The trace row
        # and its spans land on separate eventually-consistent paths; without
        # this, a consumer that opens the trace right after seeding can see
        # Spans (0) and null fields. wait_for_at_least makes the seed return a
        # fully materialized trace.
        if body.spans:
            client.search_spans(
                project_name=body.project_name,
                trace_id=str(found.id),
                max_results=len(body.spans),
                wait_for_at_least=len(body.spans),
            )
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    return NestedTraceResponse(
        id=str(found.id),
        name=body.name,
        project_id=str(found.project_id),
        span_count=len(body.spans),
    )
