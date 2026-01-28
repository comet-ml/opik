from typing import Any
from collections.abc import Callable

from opik import synchronization
from opik.api_objects import rest_stream_parser
from opik.api_objects.helpers import OptionalFilterParsedItemList
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import span_public, trace_public


def search_spans_with_filters(
    rest_client: rest_api_client.OpikApi,
    trace_id: str | None,
    project_name: str,
    filters: OptionalFilterParsedItemList | None,
    max_results: int,
    truncate: bool,
) -> list[span_public.SpanPublic]:
    spans = rest_stream_parser.read_and_parse_full_stream(
        read_source=lambda current_batch_size,
        last_retrieved_id: rest_client.spans.search_spans(
            trace_id=trace_id,
            project_name=project_name,
            filters=filters,
            limit=current_batch_size,
            truncate=truncate,
            last_retrieved_id=last_retrieved_id,
        ),
        max_results=max_results,
        parsed_item_class=span_public.SpanPublic,
    )

    return spans


def search_traces_with_filters(
    rest_client: rest_api_client.OpikApi,
    project_name: str | None,
    filters: OptionalFilterParsedItemList | None,
    max_results: int,
    truncate: bool,
) -> list[trace_public.TracePublic]:
    traces = rest_stream_parser.read_and_parse_full_stream(
        read_source=lambda current_batch_size,
        last_retrieved_id: rest_client.traces.search_traces(
            project_name=project_name,
            filters=filters,
            limit=current_batch_size,
            truncate=truncate,
            last_retrieved_id=last_retrieved_id,
        ),
        max_results=max_results,
        parsed_item_class=trace_public.TracePublic,
    )
    return traces


def search_and_wait_for_done(
    search_functor: Callable[[], list[Any]],
    wait_for_at_least: int,
    wait_for_timeout: int,
    sleep_time: float,
) -> list[Any]:
    """
    The expected behavior is to keep making repeated calls until either the specified number of
    results is found or the timeout is reached. The function will then return the best possible
    attempt results to meet these conditions.
    Args:
        search_functor: The function to call to retrieve the results.
        wait_for_at_least: The minimum number of results to return.
        wait_for_timeout: The timeout for waiting for results.
        sleep_time: The time to sleep between calls to search_functor.

    Returns:
        The function returns the results of the best possible attempt to meet both waiting conditions.
    """
    result: list[Any] = []

    def search() -> list[Any]:
        nonlocal result
        result = search_functor()
        return result

    synchronization.wait_for_done(
        check_function=lambda: len(search()) >= wait_for_at_least,
        timeout=wait_for_timeout,
        sleep_time=sleep_time,
    )

    return result
