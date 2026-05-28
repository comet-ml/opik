import atexit

import opik
from fastapi import APIRouter, Header, HTTPException

from ..opik_factory import make_opik_client
from ..schemas import TraceCreate, TraceResponse

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
