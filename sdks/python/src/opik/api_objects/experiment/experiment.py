import functools
import logging
import os
import time
from typing import Callable, List, Optional, TYPE_CHECKING

from opik.message_processing.batching import sequence_splitter
from opik.message_processing import messages, streamer
from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from opik import synchronization
from . import experiment_item, experiments_client
from .. import constants, helpers
from ...api_objects.prompt import base_prompt

if TYPE_CHECKING:
    from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)

# Default poll interval (seconds) for wait_for_evaluation_trigger. Overridable via
# OPIK_EXPERIMENT_EVALUATION_POLL_INTERVAL_SECONDS (default: 5.0).
DEFAULT_POLL_INTERVAL_SECONDS = float(
    os.getenv("OPIK_EXPERIMENT_EVALUATION_POLL_INTERVAL_SECONDS", "5.0")
)


class Experiment:
    def __init__(
        self,
        id: str,
        name: Optional[str],
        dataset_name: str,
        rest_client: rest_api_client.OpikApi,
        streamer: streamer.Streamer,
        experiments_client: experiments_client.ExperimentsClient,
        prompts: Optional[List[base_prompt.BasePrompt]] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        self._id = id
        self._name = name
        self._dataset_name = dataset_name
        self._rest_client = rest_client
        self._prompts = prompts
        self._streamer = streamer
        self._experiments_client = experiments_client
        self._tags = tags

    @property
    def id(self) -> str:
        return self._id

    @property
    def dataset_name(self) -> str:
        return self._dataset_name

    @property
    def name(self) -> str:
        if self._name is not None:
            return self._name

        name = self._rest_client.experiments.get_experiment_by_id(id=self.id).name
        self._name = name

        return name

    @property
    def tags(self) -> Optional[List[str]]:
        return self._tags

    @functools.cached_property
    def dataset_id(self) -> str:
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._dataset_name
        ).id

    @property
    def experiments_rest_client(self) -> rest_api_client.ExperimentsClient:
        return self._rest_client.experiments

    def get_experiment_data(self) -> rest_api_types.experiment_public.ExperimentPublic:
        return self._rest_client.experiments.get_experiment_by_id(id=self.id)

    def insert(
        self,
        experiment_items_references: List[experiment_item.ExperimentItemReferences],
    ) -> None:
        """
        Creates a new experiment item by linking the existing trace and dataset item.

        Args:
            experiment_items_references: The list of ExperimentItemReferences objects, containing
                trace id and dataset item id to link together into experiment item.

        Returns:
            None
        """

        experiment_item_messages = [
            messages.ExperimentItemMessage(
                id=helpers.generate_id(),
                experiment_id=self.id,
                dataset_item_id=item.dataset_item_id,
                trace_id=item.trace_id,
                project_name=item.project_name,
            )
            for item in experiment_items_references
        ]

        # Split into batches for the streamer
        batches = sequence_splitter.split_into_batches(
            experiment_item_messages,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        )

        for batch in batches:
            create_experiment_items_batch_message = (
                messages.CreateExperimentItemsBatchMessage(batch=batch)
            )
            self._streamer.put(create_experiment_items_batch_message)

    def get_items(
        self,
        max_results: Optional[int] = 10000,
        truncate: bool = False,
    ) -> List[experiment_item.ExperimentItemContent]:
        """
        Retrieves and returns a list of experiment items for this experiment.

        Args:
            max_results: Maximum number of experiment items to retrieve. Defaults to 10000 if not specified.
            truncate: Whether to truncate the items returned by the backend. Defaults to False.

        Returns:
            List of ExperimentItemContent objects for this experiment.
        """
        if max_results is None:
            max_results = 10000  # TODO: remove this once we have a proper way to get all experiment items

        return self._experiments_client.find_experiment_items_for_dataset(
            dataset_name=self.dataset_name,
            experiment_ids=[self.id],
            truncate=truncate,
            max_results=max_results,
        )

    def log_experiment_scores(
        self,
        score_results: List["score_result.ScoreResult"],
    ) -> None:
        """Log experiment-level scores to the backend."""
        experiment_scores: List[rest_api_types.ExperimentScore] = []

        for score_result_ in score_results:
            if score_result_.scoring_failed:
                continue

            experiment_score = rest_api_types.ExperimentScore(
                name=score_result_.name,
                value=score_result_.value,
            )
            experiment_scores.append(experiment_score)

        if experiment_scores:
            self._rest_client.experiments.update_experiment(
                id=self.id,
                experiment_scores=experiment_scores,
            )

    def wait_for_evaluation_trigger(
        self,
        callback: Optional[Callable[[], None]] = None,
        timeout: Optional[float] = None,
        poll_interval: Optional[float] = None,
    ) -> None:
        """
        Wait for an evaluation to be triggered from the UI.

        This method polls the experiment status until it detects that an evaluation
        has been triggered (status becomes "running") or until timeout is reached.
        Once triggered, it executes the optional callback function and then waits
        for the evaluation to complete (status becomes "completed" or "cancelled").

        Args:
            callback: Optional function to execute when evaluation is triggered.
            timeout: Maximum time in seconds to wait. If None, waits indefinitely.
            poll_interval: Time in seconds between status checks. Defaults to
                :const:`DEFAULT_POLL_INTERVAL_SECONDS` (configurable via
                ``OPIK_EXPERIMENT_EVALUATION_POLL_INTERVAL_SECONDS``, default 5.0 s).

        Returns:
            None

        Raises:
            TimeoutError: If timeout is reached before evaluation completes.
        """
        if poll_interval is None:
            poll_interval = DEFAULT_POLL_INTERVAL_SECONDS
        LOGGER.info(
            "Waiting for evaluation trigger on experiment '%s' (ID: %s)",
            self.name,
            self.id,
        )

        start_time = time.time()

        def check_status_running() -> bool:
            if timeout is not None and (time.time() - start_time) > timeout:
                raise TimeoutError(
                    f"Timeout waiting for evaluation trigger on experiment {self.id}"
                )
            exp_data = self.get_experiment_data()
            return exp_data.status == "running"

        synchronization.until(
            function=check_status_running,
            sleep=poll_interval,
            max_try_seconds=timeout or float("inf"),
        )

        LOGGER.info(
            "Evaluation triggered on experiment '%s' (ID: %s)", self.name, self.id
        )

        if callback is not None:
            LOGGER.info("Executing callback function")
            callback()

        def check_status_complete() -> bool:
            if timeout is not None and (time.time() - start_time) > timeout:
                raise TimeoutError(
                    f"Timeout waiting for evaluation completion on experiment {self.id}"
                )
            exp_data = self.get_experiment_data()
            return exp_data.status in ("completed", "cancelled")

        LOGGER.info("Waiting for evaluation to complete on experiment '%s'", self.name)

        synchronization.until(
            function=check_status_complete,
            sleep=poll_interval,
            max_try_seconds=timeout or float("inf"),
        )

        final_status = self.get_experiment_data().status
        LOGGER.info(
            "Evaluation completed on experiment '%s' with status: %s",
            self.name,
            final_status,
        )
