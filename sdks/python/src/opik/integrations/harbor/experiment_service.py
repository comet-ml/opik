"""
Experiment service for Harbor integration.

This module manages the connection between Harbor benchmark runs and Opik experiments,
enabling evaluation tracking and result visualization.

Harbor Terminology Mapping to Opik:
-----------------------------------
- **Harbor Job**: A benchmark run that evaluates one or more agents on a dataset.
  Maps to an Opik Experiment.

- **Harbor Trial**: A single agent run on a single task within a job.
  Each trial produces one Opik Trace (capturing the agent's execution).

- **Harbor Source**: The benchmark dataset being used (e.g., "terminal-bench", "swe-bench").
  Maps to an Opik Dataset. Each source gets its own dataset.

- **Harbor Task**: A specific problem/challenge within a dataset (e.g., "fix-git" task).
  Maps to an Opik Dataset Item.

Flow Overview:
--------------
1. When a Harbor job starts, this service is initialized with an experiment name.
2. For each unique source (benchmark dataset), we create/get an Opik Dataset and Experiment.
3. For each trial (agent run on a task), we:
   a. Create a dataset item for the task (or reuse existing one if task was run before)
   b. Link the trial's trace to the experiment via ExperimentItemReferences
4. This allows viewing all trial results in Opik's experiment comparison UI.
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

# Global singleton service instance (one per Harbor job)
_SERVICE: Optional["HarborExperimentService"] = None


class HarborExperimentService:
    """
    Manages Opik datasets and experiments for Harbor benchmark jobs.

    This service handles the mapping between Harbor's evaluation structure and Opik's
    experiment tracking:

    - Each Harbor source (benchmark dataset) → One Opik Dataset + One Opik Experiment
    - Each Harbor task → One Opik Dataset Item
    - Each Harbor trial → One Opik Trace, linked to the experiment

    The service uses lazy initialization - datasets and experiments are created
    on-demand when the first trial for a source is linked.

    Attributes:
        _experiment_name: Name for experiments created by this service.
        _experiment_config: Config dict stored on experiments (agent/model info).
        _client: Cached Opik client instance.
        _datasets: Map of source name → Opik Dataset.
        _experiments: Map of source name → Opik Experiment.
        _linked_trials: Set of trial names already linked (prevents duplicates).
    """

    def __init__(
        self,
        experiment_name: str,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        Initialize the experiment service.

        Args:
            experiment_name: Name for experiments. Typically includes job_id
                for uniqueness (e.g., "harbor-job-abc123").
            experiment_config: Optional config dict to store on experiments.
                Typically contains agent/model info (e.g., {"agent_name": "terminus",
                "model_name": "gpt-4o"}).
        """
        self._experiment_name = experiment_name
        self._experiment_config = experiment_config or {}
        self._experiment_config["created_from"] = "harbor"
        self._client = opik_client.get_client_cached()

        # Lazy-initialized per source (benchmark dataset)
        self._datasets: Dict[str, "Dataset"] = {}
        self._experiments: Dict[str, "Experiment"] = {}

        # Track which trials have been linked to avoid duplicates
        self._linked_trials: Set[str] = set()

    def _ensure_dataset_and_experiment(self, source: str) -> None:
        """
        Ensure an Opik Dataset and Experiment exist for the given source.

        Creates them lazily on first access. Each Harbor source (benchmark dataset)
        gets its own Opik Dataset and Experiment pair.

        Args:
            source: The Harbor source/benchmark name (e.g., "terminal-bench").
        """
        if source in self._experiments:
            return

        try:
            # Create or get the dataset for this benchmark source
            dataset = self._client.get_or_create_dataset(
                name=source,
                description=f"Harbor benchmark dataset: {source}",
            )
            self._datasets[source] = dataset
            LOGGER.info("Using dataset '%s' for Harbor source", source)

            # Create a new experiment for this job run
            experiment = self._client.create_experiment(
                dataset_name=source,
                name=self._experiment_name,
                experiment_config=self._experiment_config,
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

    def link_trial_to_experiment(
        self,
        trial_name: str,
        trace_id: str,
        source: Optional[str] = None,
        task_name: Optional[str] = None,
    ) -> None:
        """
        Link a Harbor trial's trace to the Opik experiment.

        This creates the connection between a trial's execution trace and the
        experiment, enabling the trial to appear in Opik's experiment comparison UI.

        The flow:
        1. Ensure dataset and experiment exist for the source
        2. Create or find the dataset item for this task
        3. Link the trace to the experiment via the dataset item

        Args:
            trial_name: Unique identifier for the trial (e.g., "task__abc123").
                Used to prevent duplicate linking.
            trace_id: The Opik trace ID for this trial's execution.
            source: The Harbor source/benchmark name. Defaults to "harbor-default".
            task_name: The task name within the benchmark (e.g., "fix-git").
                Used to create/find the dataset item.
        """
        source = source or "harbor-default"

        # Prevent duplicate linking of the same trial
        if trial_name in self._linked_trials:
            return

        # Ensure we have a dataset and experiment for this source
        self._ensure_dataset_and_experiment(source)

        experiment = self._experiments.get(source)
        dataset = self._datasets.get(source)

        if experiment is None or dataset is None:
            LOGGER.warning(
                "Failed to create experiment/dataset for source '%s', "
                "trial '%s' will not be linked",
                source,
                trial_name,
            )
            return

        try:
            # Insert the task as a dataset item (idempotent - duplicates are handled)
            dataset.insert([{"task_name": task_name}])

            # Find the dataset item ID for this task.
            # We search because the same task may have been inserted in a previous run,
            # and we want to reuse the existing item ID for proper experiment linking.
            items = dataset.get_items()
            dataset_item_id = None
            for item in items:
                if item.get("task_name") == task_name:
                    dataset_item_id = item.get("id")
                    break

            if dataset_item_id is None:
                LOGGER.warning("Could not find dataset item for task '%s'", task_name)
                return

            # Link the trace to the experiment via the dataset item
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
    experiment_config: Optional[Dict[str, Any]] = None,
) -> None:
    """
    Setup the experiment service lazily.

    Called when the first Harbor trial runs. Creates the global service instance
    that will be used for all subsequent trial linking. Datasets and experiments
    are created on-demand when trials are linked.

    Args:
        experiment_name: Name for the experiment. If None, auto-generates
            a timestamped name like "harbor-20241209-143000".
        experiment_config: Optional config dict to store on experiments.
            Typically contains agent/model info (e.g., {"agent_name": "terminus",
            "model_name": "gpt-4o"}).
    """
    global _SERVICE

    if _SERVICE is not None:
        LOGGER.debug("Experiment service already setup, skipping")
        return

    if experiment_name is None:
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        experiment_name = f"harbor-{timestamp}"

    _SERVICE = HarborExperimentService(
        experiment_name=experiment_name,
        experiment_config=experiment_config,
    )

    LOGGER.info("Experiment service setup for '%s'", experiment_name)


def get_service() -> Optional[HarborExperimentService]:
    """Get the current experiment service instance, or None if not initialized."""
    return _SERVICE


def reset() -> None:
    """Reset the experiment service. Used for testing."""
    global _SERVICE
    _SERVICE = None
