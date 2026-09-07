import atexit

from fastapi import APIRouter, Header, HTTPException

from ..opik_factory import make_opik_client
from ..schemas import (
    DatasetCreate,
    DatasetInsertItemsRequest,
    DatasetInsertItemsResponse,
    DatasetReadItemsRequest,
    DatasetReadItemsResponse,
    DatasetReadWithMidReadInsertRequest,
    DatasetReadWithMidReadInsertResponse,
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


@router.post("/read-items", response_model=DatasetReadItemsResponse, status_code=200)
def read_dataset_items(
    body: DatasetReadItemsRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetReadItemsResponse:
    """One `Dataset.get_items(...)`, reduced to the ids it returned, in order.

    Order is the assertion, not an incidental: `get_items` fans its pages out
    over a thread pool and reassembles them, so a read that returned the right
    items in the wrong order — or one item twice and another not at all — is
    exactly the corruption this exists to catch, and a set comparison would miss
    all of it.

    A `ValueError` from the SDK's argument validation is reported as a 200 with
    `value_error` set rather than raised: it is a documented outcome of some of
    these calls, and the caller has to be able to assert the message.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        # Only the knobs the caller actually set are passed on, so an omitted
        # one exercises the SDK's own default rather than a value chosen here.
        kwargs = {
            name: value
            for name, value in (
                ("nb_samples", body.nb_samples),
                ("num_threads", body.num_threads),
                ("chunk_size", body.chunk_size),
                ("filter_string", body.filter_string),
            )
            if value is not None
        }
        try:
            items = dataset.get_items(**kwargs)
        except ValueError as err:
            return DatasetReadItemsResponse(item_ids=[], value_error=str(err))
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    return DatasetReadItemsResponse(item_ids=[str(item["id"]) for item in items])


@router.post(
    "/read-with-mid-read-insert",
    response_model=DatasetReadWithMidReadInsertResponse,
    status_code=200,
)
def read_dataset_with_mid_read_insert(
    body: DatasetReadWithMidReadInsertRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetReadWithMidReadInsertResponse:
    """Read a dataset in chunks with an insert committed part-way through.

    `stream_items()` pins the read to the dataset version that was latest when
    iteration began, so items inserted while it runs must not affect it. Without
    that pin the read is vulnerable in a specific way: pages are addressed by
    offset and the backend sorts newest id first, so an insert shifts every page
    not yet fetched — returning one item twice and skipping another, with no
    error anywhere.

    Making that deterministic is the whole reason it runs here rather than as
    two racing HTTP calls. The reader consumes `pause_after_chunks` chunks, then
    inserts and waits for the write to commit, and only then consumes the rest —
    so every remaining page is fetched against a backend that already holds the
    new items, rather than against whichever state the network happened to
    order. The reader's look-ahead is bounded at `2 * num_threads` pages in
    flight, so pages genuinely remain unfetched at the pause provided the
    dataset is much larger than
    `chunk_size * (pause_after_chunks + 2 * num_threads)`.

    The insert goes through a client of its own: the reader is suspended
    mid-iteration on the shared one, and the write has to reach the backend the
    way an independent caller's would.
    """
    if body.pause_after_chunks < 1:
        raise HTTPException(
            status_code=422,
            detail="pause_after_chunks must be >= 1 so the read is in progress",
        )

    def insert_mid_read() -> None:
        writer = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
        try:
            writer.get_dataset(
                name=body.dataset_name, project_name=body.project_name
            ).insert(body.items)
        finally:
            writer.end(flush=True)
            atexit.unregister(writer.end)

    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    item_ids: list[str] = []
    chunk_sizes: list[int] = []
    inserted = False
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        for chunk in dataset.stream_items(
            chunk_size=body.chunk_size, num_threads=body.num_threads
        ):
            chunk_sizes.append(len(chunk))
            item_ids.extend(str(item["id"]) for item in chunk)

            if not inserted and len(chunk_sizes) == body.pause_after_chunks:
                insert_mid_read()
                inserted = True
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    if not inserted:
        raise HTTPException(
            status_code=422,
            detail=(
                f"the read produced only {len(chunk_sizes)} chunk(s), so it ended "
                f"before chunk {body.pause_after_chunks} where the insert was due; "
                "seed more items or lower chunk_size"
            ),
        )

    return DatasetReadWithMidReadInsertResponse(
        item_ids=item_ids,
        chunk_sizes=chunk_sizes,
        chunks_before_insert=body.pause_after_chunks,
        inserted=len(body.items),
    )
