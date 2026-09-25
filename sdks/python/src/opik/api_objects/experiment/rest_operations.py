import json
import math
from concurrent import futures
from typing import Any, Dict, List, Optional

from . import experiment_item
from .. import constants, rest_stream_parser
from ... import exceptions, json_helpers, rest_api
from ...rest_api.core.api_error import ApiError
from ...rest_api.core.jsonable_encoder import jsonable_encoder
from ...rest_api.types import experiment_public


def get_experiment_data_by_name(
    rest_client: rest_api.OpikApi,
    name: str,
    project_name: Optional[str],
) -> experiment_public.ExperimentPublic:
    # TODO: this method is deprecated and should be removed after
    #  deprecated Opik.get_experiment_by_name() will be removed.
    #  This function should not be used anywhere else except for deprecated logic as it is confusing and misleading

    experiments = get_experiments_data_by_name(
        rest_client, name, project_name=project_name
    )
    for experiment in experiments:
        if experiment.name == name:
            return experiment

    raise exceptions.ExperimentNotFound(f"No experiment found with the name '{name}'.")


def get_experiments_data_by_name(
    rest_client: rest_api.OpikApi,
    name: str,
    project_name: Optional[str],
    max_results: Optional[int] = None,
) -> List[experiment_public.ExperimentPublic]:
    experiments = rest_stream_parser.read_and_parse_full_stream(
        read_source=lambda current_batch_size,
        last_retrieved_id: rest_client.experiments.stream_experiments(
            name=name,
            limit=current_batch_size,
            last_retrieved_id=last_retrieved_id,
            project_name=project_name,
        ),
        max_results=max_results,
        parsed_item_class=experiment_public.ExperimentPublic,
    )

    if len(experiments) == 0:
        raise exceptions.ExperimentNotFound(
            f"No experiment(s) found with the name '{name}'."
        )

    return experiments


def find_experiment_items_for_dataset(
    rest_client: rest_api.OpikApi,
    dataset_id: str,
    experiment_ids: List[str],
    max_results: int,
    truncate: bool,
    filter_expression: Optional[str] = None,
    page_size: int = constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE,
    num_threads: int = constants.DATASET_ITEMS_READ_NUM_THREADS,
) -> List[experiment_item.ExperimentItemContent]:
    # The sequential read this replaced never issued a request for a non-positive
    # limit -- its `while len(collected) < max_results` was false on entry. Reading
    # page 1 before the limit is consulted would turn that into one Compare request,
    # and an `ApiError` where callers used to get `[]`.
    if max_results <= 0:
        return []

    experiment_ids_json = json.dumps(experiment_ids)

    def fetch_page(page_number: int) -> Dict[str, Any]:
        return _fetch_page_json(
            rest_client=rest_client,
            dataset_id=dataset_id,
            page_number=page_number,
            page_size=page_size,
            experiment_ids_json=experiment_ids_json,
            truncate=truncate,
            filter_expression=filter_expression,
        )

    collected_items: List[experiment_item.ExperimentItemContent] = []

    # The first page is read on its own because its `total` is what bounds every
    # wave after it.
    first_page = fetch_page(1)
    _collect_page(first_page, collected_items, max_results)

    if not first_page.get("content"):
        return collected_items

    # A `total` the backend omits or sends malformed leaves the page count
    # unknown, and the read falls back to walking until an empty page.
    total = first_page.get("total")
    last_page = (
        max(1, math.ceil(total / page_size))
        if isinstance(total, int) and not isinstance(total, bool) and total >= 0
        else None
    )

    next_page = 2
    while len(collected_items) < max_results:
        if last_page is not None and next_page > last_page:
            break

        # Pages are dataset-item rows, `max_results` counts experiment items, and a
        # row carries one item per experiment id -- so this is the page count only
        # for the common single-id read. Either way it costs requests, not
        # correctness: a denser page overshoots and the extra pages are trimmed to
        # `max_results`, a sparser one undershoots and takes another wave. The exact
        # count needs the items-per-row ratio, which is not known until pages arrive.
        wave_size = math.ceil((max_results - len(collected_items)) / page_size)
        if last_page is not None:
            wave_size = min(wave_size, last_page - next_page + 1)
        wave_size = min(wave_size, num_threads)

        page_numbers = list(range(next_page, next_page + wave_size))
        next_page += wave_size

        if wave_size == 1:
            pages = [fetch_page(page_numbers[0])]
        else:
            with futures.ThreadPoolExecutor(
                max_workers=wave_size,
                thread_name_prefix="opik_experiment_items_read",
            ) as pool:
                pages = list(pool.map(fetch_page, page_numbers))

        for page in pages:
            # Pages are consumed in order, so an empty one ends the read exactly
            # where a sequential walk would have stopped.
            if not page.get("content"):
                return collected_items
            _collect_page(page, collected_items, max_results)

    return collected_items


def http_client(rest_client: rest_api.OpikApi) -> Any:
    """The generated client's shared HTTP helper.

    It applies the base URL, the auth and workspace headers, the configured timeout
    and the retry policy -- every generated endpoint reaches it through this same
    private attribute. Named here once so a Fern regeneration that moves it fails with
    this message instead of an AttributeError inside a page fetch, and so the one place
    that has to change is this function.
    """
    httpx_client = getattr(
        getattr(rest_client, "_client_wrapper", None), "httpx_client", None
    )
    if httpx_client is None:
        raise exceptions.OpikException(
            "The Opik REST client does not expose its HTTP client at "
            "`_client_wrapper.httpx_client`. The generated client's layout has "
            "changed and `opik.api_objects.experiment.rest_operations.http_client` "
            "needs updating to match it."
        )
    return httpx_client


def _fetch_page_json(
    rest_client: rest_api.OpikApi,
    dataset_id: str,
    page_number: int,
    page_size: int,
    experiment_ids_json: str,
    truncate: bool,
    filter_expression: Optional[str],
) -> Dict[str, Any]:
    """One page of the Compare view as plain JSON.

    Deliberately not the generated client: parsing a page into the REST models costs
    more than fetching it -- on a 100,000-item read it was ~85% of the client time,
    walking every node to re-derive type hints. The response shape is the same either
    way, so the read stays a dict walk, as the dataset read already is.
    """
    response = http_client(rest_client).request(
        f"v1/private/datasets/{jsonable_encoder(dataset_id)}/items/experiments/items",
        method="GET",
        params={
            "page": page_number,
            "size": page_size,
            "experiment_ids": experiment_ids_json,
            "filters": filter_expression,
            "truncate": truncate,
        },
    )
    if not 200 <= response.status_code < 300:
        # `body` is the parsed payload where the backend sent JSON and the raw text
        # where it did not, which is what the generated client hands back and what
        # readers of `ApiError.body` already expect.
        try:
            body: Any = json_helpers.loads(response.content)
        except ValueError:
            body = response.text
        raise ApiError(
            status_code=response.status_code,
            headers=dict(response.headers),
            body=body,
        )

    try:
        page = json_helpers.loads(response.content)
    except ValueError:
        # The generated endpoint decodes the 2xx body inside the same `try` as the
        # error one, so an undecodable success body reaches callers as an `ApiError`
        # carrying the raw text, not as a decoder error. Keep that contract.
        raise ApiError(
            status_code=response.status_code,
            headers=dict(response.headers),
            body=response.text,
        )
    return experiment_item.require_json_type(page, dict, "the page")


def _collect_page(
    page: Dict[str, Any],
    collected_items: List[experiment_item.ExperimentItemContent],
    max_results: int,
) -> None:
    """Append one page's experiment items, stopping at ``max_results``."""
    headroom = max_results - len(collected_items)
    content = experiment_item.optional_json_list(page.get("content"), "`content`")
    if headroom <= 0 or not content:
        return

    page_items = []
    for dataset_item in content:
        experiment_item.require_json_type(dataset_item, dict, "a `content` entry")
        for experiment_item_compare in experiment_item.optional_json_list(
            dataset_item.get("experiment_items"), "`experiment_items`"
        ):
            dataset_item_data = dataset_item.get("data")
            if dataset_item_data is not None:
                experiment_item.require_json_type(dataset_item_data, dict, "`data`")
                dataset_item_data.update({"id": dataset_item.get("id")})
            page_items.append(
                experiment_item.ExperimentItemContent.from_compare_dict(
                    value=experiment_item_compare,
                    dataset_item_data=dataset_item_data,
                )
            )

    collected_items.extend(page_items[:headroom])
