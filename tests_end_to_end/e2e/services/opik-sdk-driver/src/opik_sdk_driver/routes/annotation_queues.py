import atexit

from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..visibility import wait_for_trace
from ..schemas import AnnotationQueueCreate, AnnotationQueueResponse

router = APIRouter(prefix="/annotation-queues", tags=["annotation-queues"])


@router.post("", response_model=AnnotationQueueResponse, status_code=201)
def create_annotation_queue(
    body: AnnotationQueueCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> AnnotationQueueResponse:
    # add_traces takes Trace/TracePublic objects (not bare ids), so each
    # seeded trace is read back by id first; the queue is
    # only created once every trace is confirmed visible, so a lookup
    # failure doesn't leave an orphan queue behind. ApiError is translated
    # to HTTP by the app-wide exception handler.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        traces = [wait_for_trace(client, trace_id) for trace_id in body.trace_ids]

        queue = client.create_traces_annotation_queue(
            name=body.name,
            project_name=body.project_name,
            feedback_definition_names=body.feedback_definition_names,
        )
        queue.add_traces(traces)
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    return AnnotationQueueResponse(id=queue.id, name=queue.name)


@router.delete("/{queue_id}")
def delete_annotation_queue(
    queue_id: str,
    x_opik_api_key: str | None = Header(default=None),
) -> dict[str, bool]:
    # Returns a JSON body (not 204) so the TS client, which parses every
    # success as JSON, doesn't choke on an empty body.
    client = make_opik_client(api_key=x_opik_api_key)
    try:
        client._rest_client.annotation_queues.delete_annotation_queue_batch(
            ids=[queue_id]
        )
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)
    return {"deleted": True}
