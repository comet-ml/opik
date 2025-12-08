"""
Experiment service for Harbor integration.

Manages Opik datasets and experiments for Harbor jobs, linking trial traces
to experiments for evaluation tracking.
"""

import logging
from datetime import datetime
from typing import Any, Dict, Optional, Set, TYPE_CHECKING

from opik.api_objects import opik_client
from opik.api_objects.experiment import experiment_item

if TYPE_CHECKING:
    from opik.api_objects.experiment.experiment import Experiment
    from opik.api_objects.dataset.dataset import Dataset

LOGGER = logging.getLogger(__name__)

# Global service instance
_SERVICE: Optional["HarborExperimentService"] = None


class HarborExperimentService:
    """
    Manages Opik datasets and experiments for Harbor jobs.

    This service:
    - Creates/gets a dataset for each Harbor dataset source
    - Creates an experiment for the job run
    - Links each trial's trace to the experiment as an experiment item
    """

    def __init__(
        self,
        experiment_name: str,
    ) -> None:
        """Initialize the experiment service.

        Args:
            experiment_name: Name for the experiment.
        """
        self._experiment_name = experiment_name
        self._client = opik_client.get_client_cached()

        # Dataset and experiment instances (created lazily per source)
        self._datasets: Dict[str, "Dataset"] = {}
        self._experiments: Dict[str, "Experiment"] = {}
        self._linked_trials: Set[str] = set()

    def _ensure_dataset_and_experiment(
        self,
        source: str,
        job_name: Optional[str] = None,
    ) -> None:
        """Ensure dataset and experiment exist for a source."""
        if source in self._experiments:
            return

        try:
            dataset = self._client.get_or_create_dataset(
                name=source,
                description=f"Harbor benchmark dataset: {source}",
            )
            self._datasets[source] = dataset
            LOGGER.info("Using dataset '%s' for Harbor source", source)

            experiment_config: Dict[str, Any] = {"created_from": "harbor"}
            if job_name:
                experiment_config["job_name"] = job_name

            experiment = self._client.create_experiment(
                dataset_name=source,
                name=self._experiment_name,
                experiment_config=experiment_config,
            )
            self._experiments[source] = experiment
            LOGGER.info(
                "Created experiment '%s' for dataset '%s'",
                self._experiment_name,
                source,
            )
        except Exception as e:
            LOGGER.warning(
                "Failed to create dataset/experiment for source '%s': %s",
                source,
                e,
            )

    def link_trial(
        self,
        trial_name: str,
        trace_id: str,
        source: Optional[str] = None,
        task_name: Optional[str] = None,
        agent_name: Optional[str] = None,
        model_name: Optional[str] = None,
    ) -> None:
        """Link a trial's trace to the experiment.

        Args:
            trial_name: The trial name.
            trace_id: The trace ID for this trial.
            source: The dataset source for this trial.
            task_name: The task name.
            agent_name: The agent name.
            model_name: The model name.
        """
        source = source or "harbor-default"

        if trial_name in self._linked_trials:
            return

        self._ensure_dataset_and_experiment(source)

        experiment = self._experiments.get(source)
        dataset = self._datasets.get(source)

        if experiment is None or dataset is None:
            LOGGER.debug("No experiment/dataset for source '%s'", source)
            return

        try:
            # Create dataset item
            item_data = {
                "trial_name": trial_name,
                "task_name": task_name or "unknown",
                "agent_name": agent_name or "unknown",
                "model_name": model_name,
            }
            dataset.insert([item_data])

            # Find the item to get its ID
            items = dataset.get_items()
            dataset_item_id = None
            for item in items:
                if item.get("trial_name") == trial_name:
                    dataset_item_id = item.get("id")
                    break

            if dataset_item_id is None:
                LOGGER.warning("Could not find dataset item for trial '%s'", trial_name)
                return

            # Link trace to experiment
            experiment.insert(
                [
                    experiment_item.ExperimentItemReferences(
                        dataset_item_id=dataset_item_id,
                        trace_id=trace_id,
                    )
                ]
            )

            self._linked_trials.add(trial_name)
            LOGGER.debug(
                "Linked trial '%s' (trace %s) to experiment '%s'",
                trial_name,
                trace_id,
                self._experiment_name,
            )
        except Exception as e:
            LOGGER.warning("Failed to link trial '%s' to experiment: %s", trial_name, e)


def setup_lazy(
    experiment_name: Optional[str] = None,
    source: Optional[str] = None,
) -> None:
    """Setup the experiment service lazily when the first trial runs.

    Args:
        experiment_name: Name for the experiment. Auto-generated if None.
        source: The dataset source (e.g., "terminal-bench").
    """
    global _SERVICE

    if _SERVICE is not None:
        LOGGER.debug("Experiment service already setup, skipping")
        return

    if experiment_name is None:
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        experiment_name = f"harbor-{timestamp}"

    _SERVICE = HarborExperimentService(experiment_name=experiment_name)

    # Pre-create experiment for the source if provided
    if source:
        _SERVICE._ensure_dataset_and_experiment(source)

    LOGGER.info(
        "Experiment service setup for '%s' (source=%s)", experiment_name, source
    )


def get_service() -> Optional[HarborExperimentService]:
    """Get the current experiment service instance."""
    return _SERVICE


def reset() -> None:
    """Reset the experiment service."""
    global _SERVICE
    _SERVICE = None
