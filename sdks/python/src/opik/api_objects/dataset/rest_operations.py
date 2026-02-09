from __future__ import annotations

import json
import logging
from typing import Iterator, List, Optional, Set

from opik.rest_api import OpikApi
from opik.rest_api.types import (
    dataset_item as rest_dataset_item_read,
    dataset_version_public,
)
import opik.exceptions as exceptions
from opik.message_processing import streamer
from opik.rest_client_configurator import retry_decorator
from opik.api_objects import opik_query_language, rest_stream_parser
from . import dataset, dataset_item
from .. import experiment, constants
from ..experiment import experiments_client
from ...rest_api.core.api_error import ApiError

LOGGER = logging.getLogger(__name__)


def stream_dataset_items(
    rest_client: OpikApi,
    dataset_name: str,
    nb_samples: Optional[int] = None,
    batch_size: Optional[int] = None,
    dataset_item_ids: Optional[List[str]] = None,
    filter_string: Optional[str] = None,
    dataset_version: Optional[str] = None,
) -> Iterator[dataset_item.DatasetItem]:
    """
    Stream dataset items from the backend as a generator.

    Args:
        rest_client: The REST API client.
        dataset_name: Name of the dataset to stream items from.
        nb_samples: Maximum number of items to retrieve. If None, all items are streamed.
        batch_size: Maximum number of items to fetch per batch from the backend.
        dataset_item_ids: Optional list of specific item IDs to retrieve.
        filter_string: Optional OQL filter string to filter dataset items.
        dataset_version: Optional dataset version hash to filter items by a specific version.

    Yields:
        DatasetItem objects one at a time.
    """
    if batch_size is None:
        batch_size = constants.DATASET_STREAM_BATCH_SIZE

    last_retrieved_id: Optional[str] = None
    should_retrieve_more_items = True
    items_yielded = 0
    dataset_items_ids_left: Optional[Set[str]] = (
        set(dataset_item_ids) if dataset_item_ids else None
    )

    filters: Optional[str] = None
    if filter_string:
        oql = opik_query_language.OpikQueryLanguage.for_dataset_items(filter_string)
        filter_expressions = oql.get_filter_expressions()
        if filter_expressions:
            filters = json.dumps(filter_expressions)

    while should_retrieve_more_items:

        @retry_decorator.opik_rest_retry
        def _fetch_batch() -> List[rest_dataset_item_read.DatasetItem]:
            return rest_stream_parser.read_and_parse_stream(
                stream=rest_client.datasets.stream_dataset_items(
                    dataset_name=dataset_name,
                    last_retrieved_id=last_retrieved_id,
                    steam_limit=batch_size,
                    filters=filters,
                    dataset_version=dataset_version,
                ),
                item_class=rest_dataset_item_read.DatasetItem,
                nb_samples=nb_samples,
            )

        dataset_items = _fetch_batch()

        if len(dataset_items) == 0:
            should_retrieve_more_items = False
            break

        for item in dataset_items:
            item_id = item.id
            last_retrieved_id = item_id

            if dataset_items_ids_left is not None:
                if item_id not in dataset_items_ids_left:
                    continue
                else:
                    dataset_items_ids_left.remove(item_id)

            reconstructed_item = dataset_item.DatasetItem(
                id=item.id,
                trace_id=item.trace_id,
                span_id=item.span_id,
                source=item.source,
                **item.data,
            )

            yield reconstructed_item
            items_yielded += 1

            if nb_samples is not None and items_yielded >= nb_samples:
                should_retrieve_more_items = False
                break

            if dataset_items_ids_left is not None and len(dataset_items_ids_left) == 0:
                should_retrieve_more_items = False
                break

    if dataset_items_ids_left and len(dataset_items_ids_left) > 0:
        LOGGER.warning(
            "The following dataset items were not found in the dataset: %s",
            dataset_items_ids_left,
        )


def find_version_by_name(
    rest_client: OpikApi,
    dataset_id: str,
    version_name: str,
) -> Optional[dataset_version_public.DatasetVersionPublic]:
    """
    Find a dataset version by version name.

    Args:
        rest_client: The REST API client.
        dataset_id: The dataset ID to search versions in.
        version_name: Version name to search for (e.g., 'v1', 'v2').

    Returns:
        The DatasetVersionPublic if found, None otherwise.
    """
    try:
        return rest_client.datasets.retrieve_dataset_version(
            id=dataset_id, version_name=version_name
        )
    except ApiError as e:
        if e.status_code == 404:
            return None
        raise


def get_datasets(
    rest_client: OpikApi, max_results: int = 1000, sync_items: bool = True
) -> List[dataset.Dataset]:
    page_size = 100
    datasets: List[dataset.Dataset] = []

    page = 1
    while len(datasets) < max_results:
        page_datasets = rest_client.datasets.find_datasets(
            page=page,
            size=page_size,
        )

        if len(page_datasets.content) == 0:
            break

        for dataset_fern in page_datasets.content[: (max_results - len(datasets))]:
            dataset_ = dataset.Dataset(
                name=dataset_fern.name,
                description=dataset_fern.description,
                rest_client=rest_client,
                dataset_items_count=dataset_fern.dataset_items_count,
            )

            if sync_items:
                dataset_.__internal_api__sync_hashes__()

            datasets.append(dataset_)

        page += 1

    return datasets


def get_dataset_id(rest_client: OpikApi, dataset_name: str) -> str:
    try:
        dataset_id = rest_client.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name
        ).id
    except ApiError as e:
        if e.status_code == 404:
            raise exceptions.DatasetNotFound(
                f"Dataset with the name {dataset_name} not found."
            ) from e
        raise

    return dataset_id


def get_dataset_experiments(
    rest_client: OpikApi,
    dataset_id: str,
    max_results: int,
    streamer: streamer.Streamer,
    experiments_client: experiments_client.ExperimentsClient,
) -> List[experiment.Experiment]:
    page_size = 100
    experiments: List[experiment.Experiment] = []

    page = 1
    while len(experiments) < max_results:
        page_experiments = rest_client.experiments.find_experiments(
            page=page,
            size=page_size,
            dataset_id=dataset_id,
        )

        if len(page_experiments.content) == 0:
            break

        for experiment_ in page_experiments.content[: max_results - len(experiments)]:
            experiments.append(
                experiment.Experiment(
                    id=experiment_.id,
                    name=experiment_.name,
                    dataset_name=experiment_.dataset_name,
                    rest_client=rest_client,
                    streamer=streamer,
                    experiments_client=experiments_client,
                    tags=experiment_.tags,
                )
            )

        page += 1

    return experiments
