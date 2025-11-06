from typing import List, Optional

from . import rest_operations, experiment_item
from ...rest_api import client as rest_api_client


class ExperimentsClient:
    def __init__(self, rest_client: rest_api_client.OpikApi):
        self._rest_client = rest_client

    def find_experiment_items_for_dataset(
        self,
        dataset_name: str,
        experiment_ids: List[str],
        filter_string: Optional[str] = None,
        truncate: bool = True,
        max_results: int = 1000,
    ) -> List[experiment_item.ExperimentItemContent]:
        return rest_operations.find_experiment_items_for_dataset(
            dataset_name=dataset_name,
            experiment_ids=experiment_ids,
            rest_client=self._rest_client,
            max_results=max_results,
            truncate=truncate,
            filter_string=filter_string,
        )
