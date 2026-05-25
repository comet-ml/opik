import atexit

from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..schemas import DatasetCreate, DatasetResponse

router = APIRouter(prefix="/datasets", tags=["datasets"])


@router.post("", response_model=DatasetResponse, status_code=201)
def create_dataset(
    body: DatasetCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetResponse:
    # Public create_dataset() returns a Dataset with .id directly, so no
    # post-create lookup is needed (unlike traces, where @opik.track
    # doesn't surface the ID). flush=True drains the streamer that
    # dataset.insert(items) enqueues to before we return.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.create_dataset(
            name=body.name,
            description=body.description,
            project_name=body.project_name,
        )
        if body.items:
            dataset.insert(body.items)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetResponse(id=str(dataset.id), name=dataset.name)
