from typing import List
from opik.rest_api import OpikApi
import opik.exceptions as exceptions
from . import dataset
from .. import experiment
from ...rest_api.core.api_error import ApiError


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
    rest_client: OpikApi, dataset_id: str, max_results: int = 1000
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
                    # TODO: add prompt if exists
                )
            )

        page += 1

    return experiments
