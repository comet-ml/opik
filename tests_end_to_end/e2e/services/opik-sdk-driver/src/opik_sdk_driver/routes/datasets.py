import atexit

from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..schemas import (
    DatasetCreate,
    DatasetInsertItemsRequest,
    DatasetInsertItemsResponse,
    DatasetInsertSequenceRequest,
    DatasetInsertSequenceResponse,
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
        dataset.insert(
            body.items,
            num_threads=body.num_threads,
            deduplication=body.deduplication,
        )
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertItemsResponse(dataset_id=dataset_id, inserted=len(body.items))


@router.post(
    "/insert-sequence",
    response_model=DatasetInsertSequenceResponse,
    status_code=200,
)
def insert_dataset_items_sequence(
    body: DatasetInsertSequenceRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetInsertSequenceResponse:
    """Several `Dataset.insert(...)` calls against ONE in-process Dataset object.

    `/insert-items` resolves the dataset per request, so consecutive calls each
    get a freshly constructed Dataset whose content-hash cache starts empty and
    unsynced. That hides half of what `deduplication` does: passing False marks
    the cache stale so the NEXT deduplicated insert re-syncs it from the
    backend, and a caller that re-fetches the dataset in between would re-sync
    anyway — the assertion would pass whether or not the SDK does it.

    Holding one object across the whole sequence is the only way that behaviour
    is observable from outside the SDK. Steps run in order, in this thread.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        for step in body.steps:
            dataset.insert(step.items, deduplication=step.deduplication)
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertSequenceResponse(
        dataset_id=dataset_id, steps_run=len(body.steps)
    )
