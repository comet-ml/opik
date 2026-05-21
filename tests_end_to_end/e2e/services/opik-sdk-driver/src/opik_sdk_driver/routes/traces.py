import atexit

import opik
from fastapi import APIRouter, Header, HTTPException
from opik.rest_api.core.api_error import ApiError

from ..schemas import TraceCreate, TraceResponse

router = APIRouter(prefix="/traces", tags=["traces"])


@router.post("", response_model=TraceResponse, status_code=201)
def create_trace(
    body: TraceCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> TraceResponse:
    # Use @opik.track to exercise the same code path users invoke; the
    # decorator emits a trace via the SDK's streamer thread.
    kwargs: dict[str, str] = {}
    if body.workspace:
        kwargs["workspace"] = body.workspace
    if x_opik_api_key:
        kwargs["api_key"] = x_opik_api_key
    client = opik.Opik(**kwargs) if kwargs else opik.Opik()
    # Bind the @opik.track decorator's global client to this request's auth
    # context so flush_tracker + search_traces both use the same credentials.
    opik.set_global_client(client, context_wise=True)

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
