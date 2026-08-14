import atexit

from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..schemas import (
    DatasetCreate,
    DatasetInsertItemsRequest,
    DatasetInsertItemsResponse,
    DatasetResponse,
)

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


@router.post(
    "/insert-items",
    response_model=DatasetInsertItemsResponse,
    status_code=200,
)
def insert_dataset_items(
    body: DatasetInsertItemsRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetInsertItemsResponse:
    """Insert items into an existing dataset by name, as ONE dataset version.

    Mirrors test-suites/insert-items: resolves the dataset within the caller's
    `project_name` scope, since same-named datasets can exist across projects.
    Each call is one `Dataset.insert(...)`, which is the unit a version is cut
    on — the SDK splits the items into batches of 1000 internally, and those
    batches must not become versions of their own.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        dataset.insert(body.items, num_threads=body.num_threads)
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertItemsResponse(dataset_id=dataset_id, inserted=len(body.items))
