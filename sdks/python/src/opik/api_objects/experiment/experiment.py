import functools
import logging
from concurrent import futures
from typing import List, Optional, TYPE_CHECKING

from opik.message_processing.batching import sequence_splitter
from opik.message_processing import messages, streamer
from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from . import bulk_converters, bulk_item, experiment_item, experiments_client
from .. import constants, helpers, rest_helpers
from ...api_objects.prompt import base_prompt
from ... import exceptions

if TYPE_CHECKING:
    from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)


def _raise_on_oversized_items(
    rest_items: List[
        rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView
    ],
) -> None:
    """Reject items that cannot fit in a request on their own.

    ``split_into_batches`` puts an oversized item in a batch by itself rather
    than dropping it, which would send a request the backend is guaranteed to
    reject with a 422. Failing here names the offending item instead.

    The bound is inclusive, matching ``split_into_batches``: an item measuring
    exactly the limit already fills a batch on its own, leaving no room for the
    request envelope.
    """
    failure_reasons = [
        f"items[{index}] is {size_MB:.1f}MB, which is at or above the "
        f"{constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB}MB per-request limit"
        for index, size_MB in (
            (index, sequence_splitter.get_payload_size_MB(item))
            for index, item in enumerate(rest_items)
        )
        if size_MB >= constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB
    ]

    if failure_reasons:
        raise exceptions.ValidationError(
            prefix="batch_upload_items", failure_reasons=failure_reasons
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
        project_name: Optional[str] = None,
    ) -> None:
        self._id = id
        self._name = name
        self._dataset_name = dataset_name
        self._rest_client = rest_client
        self._prompts = prompts
        self._streamer = streamer
        self._experiments_client = experiments_client
        self._tags = tags
        self._project_name = project_name

    @property
    def project_name(self) -> Optional[str]:
        return self._project_name

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

    @property
    def prompts(self) -> Optional[List[base_prompt.BasePrompt]]:
        return self._prompts

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
                execution_policy=item.execution_policy,
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

    def _bulk_upload_batch_with_retry(
        self,
        batch: List[rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView],
        project_name: Optional[str],
    ) -> None:
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.experiments.experiment_items_bulk(
                experiment_id=self.id,
                experiment_name=self.name,
                dataset_name=self.dataset_name,
                project_name=project_name,
                items=batch,
            ),
            operation_name="experiment_items_bulk",
        )
        LOGGER.debug(
            "Successfully sent experiment items bulk batch of size %d", len(batch)
        )

    def batch_upload_items(
        self,
        items: List[bulk_item.ExperimentItemBulkRecord],
        project_name: Optional[str] = None,
        num_threads: int = 1,
    ) -> None:
        """
        Upload experiment items together with their traces, spans and feedback scores.

        Unlike :meth:`insert`, which only links already-existing traces to dataset
        items, this method creates the traces and spans as part of the same request.

        Items are validated up front, split into batches that respect the backend's
        1000-item and 4MB-per-request limits, and sent with automatic retry on rate
        limiting (HTTP 429).

        If a batch fails, the exception propagates and the remaining batches are
        not sent, leaving the experiment partially populated. Rate-limit retries
        re-send the identical payload, so they never duplicate anything. Calling
        this method again, however, mints new ids for any trace or span left
        without one, which would duplicate whatever the first call did manage to
        write — set ``id`` on the traces and spans you pass in if you intend to
        retry a failed upload.

        Args:
            items: The experiment items to upload. Each item must provide exactly
                one of ``evaluate_task_result`` or ``trace``.
            project_name: Project for traces auto-created from items that provide
                ``evaluate_task_result``. Defaults to the experiment's project;
                blank is treated as unset. When set, every item-level
                ``trace.project_name`` must match it.
            num_threads: Number of batches to upload concurrently. Defaults to 1
                (sequential). Raising it trades ordering and a higher chance of
                being rate limited for throughput. Capped at the number of
                batches and at
                ``constants.EXPERIMENT_ITEMS_BULK_MAX_THREADS``.

        Returns:
            None

        Raises:
            opik.exceptions.ValidationError: If any item fails validation, if a
                single item is too large to fit in one request, or if
                ``num_threads`` is less than 1.
        """
        if num_threads < 1:
            raise exceptions.ValidationError(
                prefix="batch_upload_items",
                failure_reasons=[f"num_threads must be at least 1, got {num_threads}"],
            )

        if not items:
            return

        resolved_project_name = (
            project_name if project_name is not None else self._project_name
        )
        # The backend annotates project_name with @Pattern(NULL_OR_NOT_BLANK), so a
        # blank string is rejected outright rather than falling back to the default
        # project. Treat it as unset, which is what the caller meant.
        if resolved_project_name is not None and not resolved_project_name.strip():
            resolved_project_name = None

        bulk_converters.validate_records(items, project_name=resolved_project_name)

        rest_items = [bulk_converters.to_rest_record(item) for item in items]

        _raise_on_oversized_items(rest_items)

        batches = sequence_splitter.split_into_batches(
            rest_items,
            max_payload_size_MB=constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB,
            max_length=constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE,
        )

        LOGGER.debug(
            "Uploading %d experiment items in %d batch(es) using %d thread(s)",
            len(rest_items),
            len(batches),
            num_threads,
        )

        if num_threads == 1:
            for batch in batches:
                self._bulk_upload_batch_with_retry(
                    batch, project_name=resolved_project_name
                )
            return

        # Deliberately not a `with` block: ThreadPoolExecutor.__exit__ always
        # calls shutdown(wait=True), which would re-join batches we just chose
        # not to wait for and park the caller behind a batch stuck in the
        # rate-limit retry loop.
        # More workers than batches is pure waste, and an unbounded caller-supplied
        # value would spawn a thread per batch.
        worker_count = min(
            num_threads, len(batches), constants.EXPERIMENT_ITEMS_BULK_MAX_THREADS
        )
        pool = futures.ThreadPoolExecutor(
            max_workers=worker_count, thread_name_prefix="opik_experiment_items_bulk"
        )
        submitted = [
            pool.submit(
                self._bulk_upload_batch_with_retry,
                batch,
                project_name=resolved_project_name,
            )
            for batch in batches
        ]
        try:
            for future in futures.as_completed(submitted):
                future.result()
        except BaseException:
            # Fail fast: drop batches that have not started and return without
            # joining the ones already in flight.
            pool.shutdown(wait=False, cancel_futures=True)
            raise
        else:
            pool.shutdown(wait=True)

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
            project_name=self._project_name,
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
