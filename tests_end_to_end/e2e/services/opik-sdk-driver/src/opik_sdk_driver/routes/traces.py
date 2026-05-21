import atexit

import opik
from fastapi import APIRouter, HTTPException
from opik.rest_api.core.api_error import ApiError

from ..schemas import TraceCreate, TraceResponse

router = APIRouter(prefix="/traces", tags=["traces"])


@router.post("", response_model=TraceResponse, status_code=201)
def create_trace(body: TraceCreate) -> TraceResponse:
    # Use @opik.track to exercise the same code path users invoke; the
    # decorator emits a trace via the SDK's streamer thread.
    client = opik.Opik(workspace=body.workspace) if body.workspace else opik.Opik()

    @opik.track(name=body.name, project_name=body.project_name)
    def _emit(_input_value: str) -> str:
        return body.output

    try:
        try:
            _emit(body.input)
            opik.flush_tracker()
            traces = client.search_traces(
                project_name=body.project_name,
                filter_string=f'name = "{body.name}"',
                max_results=1,
                wait_for_at_least=1,
            )
        except ApiError as e:
            raise HTTPException(status_code=e.status_code, detail=e.body) from e
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
