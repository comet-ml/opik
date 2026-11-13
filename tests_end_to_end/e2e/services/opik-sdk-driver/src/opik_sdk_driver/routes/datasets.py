import atexit
import datetime
import enum
import uuid
from typing import Any

from fastapi import APIRouter, Header, HTTPException
from opik import json_helpers

from ..opik_factory import make_opik_client
from ..schemas import (
    DatasetCreate,
    DatasetInsertItemsRequest,
    DatasetInsertItemsResponse,
    DatasetInsertItemsSessionRequest,
    DatasetInsertItemsSessionResponse,
    DatasetInsertRetryAfterFailureRequest,
    DatasetInsertRetryAfterFailureResponse,
    DatasetInsertTypedItemRequest,
    DatasetInsertTypedItemResponse,
    DatasetReadItemsRequest,
    DatasetReadItemsResponse,
    DatasetReadWithMidReadInsertRequest,
    DatasetReadWithMidReadInsertResponse,
    DatasetResponse,
    TypedValue,
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

    A `ValueError` out of `Dataset.insert` is reported as a 200 with
    `value_error` set rather than raised, as on `/datasets/read-items`: argument
    rejection is a documented outcome of some of these calls and the caller has
    to be able to assert the message.

    The catch is the whole call, not just its argument validation, because the
    two are not separable from out here — a `ValueError` raised mid-upload
    surfaces the same way. It is reported rather than swallowed, so a caller
    that cares whether anything was written must read the dataset back instead
    of trusting `inserted`; `dataset-insert-thread-clamp.spec.ts` does exactly
    that.
    """
    client = make_opik_client(
        workspace=body.workspace,
        api_key=x_opik_api_key,
        enable_json_request_compression=body.enable_json_request_compression,
    )
    # The setting the client was actually built with, read off its own config
    # rather than from the request: this is what decides whether the upload's
    # bodies are gzipped, so it is the only honest thing to report back.
    compression_enabled = client.config.enable_json_request_compression
    value_error: str | None = None
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        try:
            dataset.insert(
                body.items,
                num_threads=body.num_threads,
                deduplication=body.deduplication,
            )
        except ValueError as err:
            value_error = str(err)
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertItemsResponse(
        dataset_id=dataset_id,
        inserted=0 if value_error else len(body.items),
        compression_enabled=compression_enabled,
        value_error=value_error,
    )


@router.post(
    "/insert-items-session",
    response_model=DatasetInsertItemsSessionResponse,
    status_code=200,
)
def insert_dataset_items_session(
    body: DatasetInsertItemsSessionRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetInsertItemsSessionResponse:
    """Run several `Dataset.insert(...)` calls against ONE `Dataset` object.

    Same per-call semantics as `/insert-items` — one insert, one version — but
    the object (and therefore its local content-hash cache) survives across the
    whole sequence, so a caller can exercise how one insert affects the next.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        for call in body.inserts:
            dataset.insert(
                call.items,
                num_threads=call.num_threads,
                deduplication=call.deduplication,
            )
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertItemsSessionResponse(
        dataset_id=dataset_id,
        inserted=[len(call.items) for call in body.inserts],
    )


class _AbortedInsert(RuntimeError):
    """Raised by the generator feeding the aborted insert, so the cause is unambiguous.

    A bespoke type rather than a bare RuntimeError: the route asserts that the
    insert failed for the reason it arranged, and any other RuntimeError out of
    the SDK (a failed upload body, a transport error) would otherwise be
    reported as a successfully-aborted funnel and the test would assert the
    retry against a state it does not understand.
    """


@router.post(
    "/insert-retry-after-failed-funnel",
    response_model=DatasetInsertRetryAfterFailureResponse,
    status_code=200,
)
def insert_retry_after_failed_funnel(
    body: DatasetInsertRetryAfterFailureRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetInsertRetryAfterFailureResponse:
    """Abort an insert part-way, then retry the same items on the SAME `Dataset`.

    `Dataset.insert` accepts any iterable and consumes it lazily, hashing each
    item as it is yielded and before its batch is sent. So a source that raises
    leaves the object's hash cache holding items that never reached the backend
    — and a retry that trusted that cache would skip them as duplicates and
    store nothing (opik#9393, issue-9376). The SDK's fix marks the cache out of
    sync whenever the funnel does not complete, which forces the retry to
    re-sync against what the backend really holds.

    Three inserts, in order, all on one object:

      1. the items as a generator that yields every one of them and THEN raises,
      2. the same items as a list, deduplication left at its default — the retry,
      3. the same items again, so deduplication is shown still to work.

    Item counts are read between the steps through a separate `get_dataset()`:
    a second `Dataset` object over the same backend dataset, so the read cannot
    disturb the subject's cache. Public surface throughout — no internal flag is
    inspected, because what a caller needs to assert is that the items landed,
    not how the SDK decided to send them.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)

    def count_stored() -> int:
        return len(
            client.get_dataset(
                name=body.dataset_name, project_name=body.project_name
            ).get_items()
        )

    def yield_then_raise() -> Any:
        for item in body.items:
            yield item
        raise _AbortedInsert(
            f"aborted after yielding {len(body.items)} item(s) — the funnel never completed"
        )

    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )

        aborted_insert_error: str | None = None
        try:
            dataset.insert(yield_then_raise())
        except _AbortedInsert as err:
            aborted_insert_error = str(err)

        if aborted_insert_error is None:
            # Loud rather than reported: the scenario did not happen, so every
            # count below would describe a different test.
            raise HTTPException(
                status_code=500,
                detail=(
                    "insert-retry-after-failed-funnel: the aborted insert completed "
                    "without raising, so the failed-funnel state was never reached"
                ),
            )

        items_after_aborted_insert = count_stored()

        dataset.insert(body.items)
        items_after_retry = count_stored()

        dataset.insert(body.items)
        items_after_repeat = count_stored()

        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertRetryAfterFailureResponse(
        dataset_id=dataset_id,
        aborted_insert_error=aborted_insert_error,
        items_after_aborted_insert=items_after_aborted_insert,
        items_after_retry=items_after_retry,
        items_after_repeat=items_after_repeat,
    )


def _materialize(field: str, spec: TypedValue) -> Any:
    """The real Python object a `TypedValue` describes.

    Raises 422 rather than falling back to the JSON form on a value the kind
    cannot be built from: a caller asking for a `uuid` and silently receiving
    the string it sent would get a green test asserting nothing, because a
    string round-trips trivially.
    """
    try:
        if spec.kind == "float":
            return float(spec.value)
        if spec.kind == "uuid":
            return uuid.UUID(spec.value)
        if spec.kind == "datetime":
            return datetime.datetime.fromisoformat(spec.value)
        if spec.kind == "enum":
            # A one-member Enum built here rather than a fixed catalogue, so the
            # caller decides the value its member must serialise to.
            return enum.Enum(f"{field.title()}Probe", {"MEMBER": spec.value}).MEMBER
        if spec.kind == "set":
            return set(spec.value)
        if spec.kind == "tuple":
            return tuple(spec.value)
    except (TypeError, ValueError) as err:
        raise HTTPException(
            status_code=422,
            detail=(
                f"field '{field}': {spec.value!r} is not a valid {spec.kind} ({err})"
            ),
        ) from err

    # Unreachable while `kind` is a Literal pydantic validates, which is the
    # point: a kind added to the schema and not handled above must fail here
    # rather than fall out of the function returning None and be inserted as a
    # null the caller would have to notice.
    raise HTTPException(
        status_code=422, detail=f"field '{field}': unhandled kind {spec.kind}"
    )


@router.post(
    "/insert-typed-item",
    response_model=DatasetInsertTypedItemResponse,
    status_code=200,
)
def insert_typed_dataset_item(
    body: DatasetInsertTypedItemRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> DatasetInsertTypedItemResponse:
    """One `Dataset.insert([item])` whose content carries real Python types.

    See `DatasetInsertTypedItemRequest` for why the types are built here rather
    than sent. `accelerated` reports which encoder this process holds, which the
    caller cannot otherwise know — it is diagnostic, and no behaviour here
    depends on it.
    """
    content = {
        field: _materialize(field, spec) for field, spec in body.typed_content.items()
    }

    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        dataset.insert([content], deduplication=body.deduplication)
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return DatasetInsertTypedItemResponse(
        dataset_id=dataset_id,
        inserted=1,
        accelerated=json_helpers.ACCELERATED,
    )


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
    # How many chunks had actually been consumed when the insert ran. Observed
    # rather than echoed from the request: a caller asserting the read was
    # genuinely mid-flight has to be reading a number the read produced, not one
    # it supplied.
    chunks_before_insert: int | None = None
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        for chunk in dataset.stream_items(
            chunk_size=body.chunk_size, num_threads=body.num_threads
        ):
            chunk_sizes.append(len(chunk))
            item_ids.extend(str(item["id"]) for item in chunk)

            if chunks_before_insert is None and len(chunk_sizes) == body.pause_after_chunks:
                insert_mid_read()
                chunks_before_insert = len(chunk_sizes)
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    if chunks_before_insert is None:
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
        chunks_before_insert=chunks_before_insert,
        inserted=len(body.items),
    )
