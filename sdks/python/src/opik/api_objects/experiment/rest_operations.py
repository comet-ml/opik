import json
import math
from concurrent import futures
from typing import List, Optional

from . import experiment_item
from .. import constants, rest_stream_parser
from ... import exceptions, rest_api
from ...rest_api.types import dataset_item_page_compare, experiment_public


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
    experiment_ids_json = json.dumps(experiment_ids)

    def fetch_page(
        page_number: int,
    ) -> dataset_item_page_compare.DatasetItemPageCompare:
        return rest_client.datasets.find_dataset_items_with_experiment_items(
            id=dataset_id,
            page=page_number,
            size=page_size,
            experiment_ids=experiment_ids_json,
            truncate=truncate,
            filters=filter_expression,
        )

    collected_items: List[experiment_item.ExperimentItemContent] = []

    # The first page is read on its own because its `total` is what bounds every
    # wave after it.
    first_page = fetch_page(1)
    _collect_page(first_page, collected_items, max_results)

    if not first_page.content:
        return collected_items

    last_page = (
        max(1, math.ceil(first_page.total / page_size))
        if first_page.total is not None
        else None
    )

    next_page = 2
    while len(collected_items) < max_results:
        if last_page is not None and next_page > last_page:
            break

        # A dataset item almost always carries one experiment item, so this is
        # the page count the rest of the read needs; a sparser page only costs
        # another wave rather than a wrong result.
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
            if not page.content:
                return collected_items
            _collect_page(page, collected_items, max_results)

    return collected_items


def _collect_page(
    page: dataset_item_page_compare.DatasetItemPageCompare,
    collected_items: List[experiment_item.ExperimentItemContent],
    max_results: int,
) -> None:
    """Append one page's experiment items, stopping at ``max_results``."""
    headroom = max_results - len(collected_items)
    if headroom <= 0 or not page.content:
        return

    page_items = []
    for dataset_item in page.content:
        if dataset_item.experiment_items is None:
            continue
        for experiment_item_compare in dataset_item.experiment_items:
            dataset_item_data = dataset_item.data
            if dataset_item_data is not None:
                dataset_item_data.update({"id": dataset_item.id})
            page_items.append(
                experiment_item.ExperimentItemContent.from_rest_experiment_item_compare(
                    value=experiment_item_compare,
                    dataset_item_data=dataset_item_data,
                )
            )

    collected_items.extend(page_items[:headroom])
