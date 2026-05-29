import logging
from typing import Any, Dict, List, Optional, Union

from ..api_objects import opik_client
from ..api_objects.dataset import dataset, dataset_item
from . import samplers

LOGGER = logging.getLogger(__name__)

EVALUATION_STREAM_DATASET_BATCH_SIZE = 200


def resolve_project_name(
    value_from_dataset: Optional[str],
    value_from_user: Optional[str],
    caller_name: str,
) -> Optional[str]:
    """
    Resolve which project name to use for evaluation.

    Prefers the dataset's ``project_name`` when set. If the caller also passed
    a ``project_name``, log a deprecation warning and ignore the override so
    traces land in the dataset's project.

    Falls back to the caller's ``project_name`` when the dataset has none, to
    preserve backward compatibility during the deprecation period.
    """
    if value_from_dataset is None:
        return value_from_user

    if value_from_user is not None:
        LOGGER.warning(
            "The `project_name` parameter of `%s()` is deprecated and will be "
            "removed in a future version. The dataset's project ('%s') will "
            "be used instead of the provided value ('%s').",
            caller_name,
            value_from_dataset,
            value_from_user,
        )

    return value_from_dataset


def merge_blueprint_into_config(
    client: "opik_client.Opik",
    blueprint_id: str,
    experiment_config: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    """Add blueprint reference to experiment_config under ``agent_configuration``."""
    experiment_config = dict(experiment_config) if experiment_config else {}
    agent_config: Dict[str, str] = {"_blueprint_id": blueprint_id}
    try:
        blueprint = client._rest_client.agent_configs.get_blueprint_by_id(
            blueprint_id=blueprint_id,
        )
        if blueprint.name:
            agent_config["blueprint_version"] = blueprint.name
    except Exception:
        LOGGER.debug("Failed to fetch blueprint %s", blueprint_id, exc_info=True)
    experiment_config["agent_configuration"] = agent_config
    return experiment_config


def resolve_dataset_items(
    dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
    nb_samples: Optional[int],
    dataset_item_ids: Optional[List[str]],
    dataset_sampler: Optional[samplers.BaseDatasetSampler],
    dataset_filter_string: Optional[str],
) -> List[dataset_item.DatasetItem]:
    """
    Materialize the dataset items to evaluate.

    The full list is returned so callers can compute ``len(items)`` directly
    and snapshot resolved ids for resume without an inversion-of-control hook.
    Evaluation workloads are dominated by per-item LLM cost, so the memory
    saved by lazy streaming was negligible compared to the complexity it
    pushed onto callers (separate iterator / total / materialization hook).
    """
    items = list(
        dataset_.__internal_api__stream_items_as_dataclasses__(
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
            batch_size=EVALUATION_STREAM_DATASET_BATCH_SIZE,
            filter_string=dataset_filter_string,
        )
    )
    if dataset_sampler is None:
        return items

    sampled = dataset_sampler.sample(items)
    if not isinstance(sampled, list):
        raise TypeError(
            "BaseDatasetSampler.sample() must return a list; got "
            f"{type(sampled).__name__}. Streaming samplers are not supported."
        )
    return sampled
