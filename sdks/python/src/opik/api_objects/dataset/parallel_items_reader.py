"""Parallel reader for dataset items built on the paginated items endpoint.

Deliberately bypasses the Fern-generated REST client. The generated path
validates every field and rebuilds each item as a pydantic model, and on
datasets of hundreds of thousands of items that conversion, not the network,
dominates the wall-clock time. This reader issues the same requests through the
SDK's own httpx client and hands back the raw JSON dicts.

The paginated endpoint is used instead of the cursor-chained
``/items/stream``: pages are addressable independently, which is what makes
fetching them concurrently possible at all.
"""

from __future__ import annotations

import itertools
import logging
import math
from concurrent import futures
from typing import Any, Callable, Deque, Dict, Iterator, List, Optional
import collections

import opik.exceptions as exceptions
from opik.rest_api import client as rest_api_client
from opik.rest_api.core import api_error as rest_api_error
from opik.rest_client_configurator import retry_decorator

LOGGER = logging.getLogger(__name__)

_MALFORMED_PAGE = (
    "Malformed response from the dataset items endpoint. "
    "The page count for the rest of the read is derived from this response, so "
    "continuing would silently return only part of the dataset."
)


def stream_item_chunks(
    rest_client: rest_api_client.OpikApi,
    dataset_id: str,
    chunk_size: int,
    num_threads: int,
    max_items: Optional[int] = None,
    filters: Optional[str] = None,
    dataset_version: Optional[str] = None,
) -> Iterator[List[Dict[str, Any]]]:
    """Yield dataset items in page-sized chunks, fetching pages concurrently.

    Args:
        rest_client: The REST API client. Only its httpx client and auth
            headers are used; no generated endpoint method is called.
        dataset_id: Id of the dataset to read.
        chunk_size: Number of items per page, and therefore per yielded chunk.
        num_threads: Number of pages fetched concurrently.
        max_items: Stop after this many items. ``None`` reads the whole dataset.
        filters: Serialized JSON filter expressions, as the endpoint's
            ``filters`` query parameter expects them.
        dataset_version: Version hash or tag to pin the read to. ``None`` reads
            the current state.

    Yields:
        Lists of raw REST item dicts, in page order. The last chunk may be
        shorter than ``chunk_size``; empty chunks are never yielded.
    """
    items_yielded = 0

    for page_items in _read_pages(
        fetch_page=_build_page_fetcher(
            rest_client=rest_client,
            dataset_id=dataset_id,
            chunk_size=chunk_size,
            filters=filters,
            dataset_version=dataset_version,
        ),
        chunk_size=chunk_size,
        num_threads=num_threads,
        max_items=max_items,
    ):
        if max_items is not None:
            page_items = page_items[: max_items - items_yielded]

        if page_items:
            yield page_items
            items_yielded += len(page_items)

        if max_items is not None and items_yielded >= max_items:
            return


def _read_pages(
    fetch_page: Callable[[int], Dict[str, Any]],
    chunk_size: int,
    num_threads: int,
    max_items: Optional[int],
) -> Iterator[List[Dict[str, Any]]]:
    """Yield the raw contents of every page that holds a wanted item, in order.

    The first page is fetched on its own because its ``total`` is what tells us
    how many pages there are to fan out over.
    """
    first_page = fetch_page(1)
    total = _page_total(first_page)
    yield _page_items(first_page)

    wanted = total if max_items is None else min(total, max_items)
    last_page = math.ceil(wanted / chunk_size)
    if last_page <= 1:
        return

    remaining_pages = iter(range(2, last_page + 1))
    worker_count = min(num_threads, last_page - 1)

    LOGGER.debug(
        "Reading %d dataset items over %d pages of %d using %d thread(s)",
        wanted,
        last_page,
        chunk_size,
        worker_count,
    )

    # Deliberately not a `with` block: its __exit__ is shutdown(wait=True), and
    # because the yields below happen inside it, closing the generator -- an
    # early `break`, or garbage collection -- would block the caller until every
    # outstanding page came back. At a 2000-item page that is a visible stall on
    # what looks like a plain `break`, and at GC time it would surface on an
    # unrelated line.
    pool = futures.ThreadPoolExecutor(
        max_workers=worker_count, thread_name_prefix="opik_dataset_items_read"
    )
    try:
        # Two pages in flight per worker: enough that a worker always has the
        # next page waiting, while keeping the reader's memory bounded by the
        # look-ahead rather than by the size of the dataset.
        in_flight: Deque[futures.Future] = collections.deque(
            pool.submit(fetch_page, page)
            for page in itertools.islice(remaining_pages, worker_count * 2)
        )

        while in_flight:
            page = in_flight.popleft().result()

            next_page = next(remaining_pages, None)
            if next_page is not None:
                in_flight.append(pool.submit(fetch_page, next_page))

            yield _page_items(page)
    finally:
        # Drops the pages that haven't started and doesn't join the ones that
        # have, so abandoning the iterator returns immediately. On the normal
        # path there is nothing left in flight, so this is a no-op.
        pool.shutdown(wait=False, cancel_futures=True)


def _build_page_fetcher(
    rest_client: rest_api_client.OpikApi,
    dataset_id: str,
    chunk_size: int,
    filters: Optional[str],
    dataset_version: Optional[str],
) -> Callable[[int], Dict[str, Any]]:
    httpx_client = rest_client._client_wrapper.httpx_client
    path = f"v1/private/datasets/{dataset_id}/items"

    @retry_decorator.opik_rest_retry
    def fetch_page(page: int) -> Dict[str, Any]:
        response = httpx_client.request(
            path,
            method="GET",
            params={
                "page": page,
                "size": chunk_size,
                "version": dataset_version,
                "filters": filters,
            },
        )

        # The httpx client returns the response as-is, so the status has to be
        # turned into an ApiError by hand for the retry decorator to see the
        # retryable ones.
        if response.status_code != 200:
            raise rest_api_error.ApiError(
                status_code=response.status_code,
                headers=dict(response.headers),
                body=response.text,
            )

        result: Dict[str, Any] = response.json()
        return result

    return fetch_page


def _page_items(page: Dict[str, Any]) -> List[Dict[str, Any]]:
    content = page.get("content")
    if content is None:
        content = []
    if not isinstance(content, list):
        raise exceptions.OpikException(
            f"{_MALFORMED_PAGE} Expected 'content' to be a list, got "
            f"{type(content).__name__}."
        )
    return content


def _page_total(page: Dict[str, Any]) -> int:
    """Read the item count the rest of the read is planned against.

    Validated rather than defaulted: the page count is derived from ``total``,
    so a missing or non-numeric value would silently cap the read at the first
    page and hand back part of the dataset as if it were all of it.
    """
    if not isinstance(page, dict):
        raise exceptions.OpikException(
            f"{_MALFORMED_PAGE} Expected a JSON object, got {type(page).__name__}."
        )

    total = page.get("total")
    # bool is an int subclass, and True would silently read as 1 item.
    if isinstance(total, bool) or not isinstance(total, int):
        raise exceptions.OpikException(
            f"{_MALFORMED_PAGE} Expected an integer 'total', got {total!r}."
        )
    if total < 0:
        raise exceptions.OpikException(
            f"{_MALFORMED_PAGE} Expected a non-negative 'total', got {total}."
        )

    return total
