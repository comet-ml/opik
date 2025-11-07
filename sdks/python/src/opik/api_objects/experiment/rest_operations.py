import json
from typing import List, Optional

from . import experiment_item
from .. import rest_stream_parser
from ... import exceptions, rest_api
from ...rest_api.types import experiment_public


def get_experiment_data_by_name(
    rest_client: rest_api.OpikApi, name: str
) -> experiment_public.ExperimentPublic:
    # TODO: this method is deprecated and should be removed after
    #  deprecated Opik.get_experiment_by_name() will be removed.
    #  This function should not be used anywhere else except for deprecated logic as it is confusing and misleading

    experiments = get_experiments_data_by_name(rest_client, name)
    for experiment in experiments:
        if experiment.name == name:
            return experiment

    raise exceptions.ExperimentNotFound(f"No experiment found with the name '{name}'.")


def get_experiments_data_by_name(
    rest_client: rest_api.OpikApi,
    name: str,
    max_results: Optional[int] = None,
) -> List[experiment_public.ExperimentPublic]:
    experiments = rest_stream_parser.read_and_parse_full_stream(
        read_source=lambda current_batch_size,
        last_retrieved_id: rest_client.experiments.stream_experiments(
            name=name,
            limit=current_batch_size,
            last_retrieved_id=last_retrieved_id,
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
) -> List[experiment_item.ExperimentItemContent]:
    PAGE_SIZE = 100

    collected_items: List[experiment_item.ExperimentItemContent] = []
    experiment_ids_json = json.dumps(experiment_ids)

    page_number = 1
    while len(collected_items) < max_results:
        page_dataset_items = (
            rest_client.datasets.find_dataset_items_with_experiment_items(
                id=dataset_id,
                page=page_number,
                size=PAGE_SIZE,
                experiment_ids=experiment_ids_json,
                truncate=truncate,
                filters=filter_expression,
            )
        )

        if not page_dataset_items.content:
            break

        # Build a flat list of experiment-item compares for this page in a readable way
        experiment_items = []
        for dataset_item in page_dataset_items.content:
            # Guard if dataset_item.experiment_items might be None
            if dataset_item.experiment_items is not None:
                for experiment_item_compare in dataset_item.experiment_items:
                    # Convert to domain objects
                    dataset_item_data = dataset_item.data
                    if dataset_item_data is not None:
                        dataset_item_data.update({"id": dataset_item.id})
                    experiment_item_content = experiment_item.ExperimentItemContent.from_rest_experiment_item_compare(
                        value=experiment_item_compare,
                        dataset_item_data=dataset_item_data,
                    )

                    experiment_items.append(experiment_item_content)

        if not experiment_items:
            page_number += 1
            continue

        remaining = max_results - len(collected_items)

        collected_items.extend(experiment_items[:remaining])

        page_number += 1

    return collected_items
