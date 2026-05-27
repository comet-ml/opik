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
    """Wraps client.create_dataset(...) + optional dataset.insert(items).

    flush=True on client.end() drains the streamer that insert() enqueues to.
    """
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
