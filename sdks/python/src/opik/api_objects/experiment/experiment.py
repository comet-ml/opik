import collections.abc
import functools
import logging
import threading
from concurrent import futures
from typing import Iterable, Iterator, List, Optional, Sequence, TYPE_CHECKING

from opik.message_processing.batching import sequence_splitter
from opik.message_processing import messages, streamer
from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from . import bulk_converters, bulk_item, experiment_item, experiments_client
from .. import constants, helpers, rest_helpers
from ...api_objects.prompt import base_prompt
from ...rest_api.core.api_error import ApiError
from ... import exceptions

if TYPE_CHECKING:
    from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)

# The backend caps a bulk request through a bean-validation constraint (@MaxRequestSize /
# MaxRequestSizeValidator in opik-backend), which answers 422 carrying this message; the byte
# count it appends is configurable, so it stays out of the match. A 413 comes only from the
# pre-parse Content-Length filter, which a deployment can tune below our batch cap.
_TOO_LARGE_MESSAGE = "request size exceeds the maximum allowed size"


def _is_batch_too_large(error: ApiError) -> bool:
    if error.status_code == 413:
        return True
    if error.status_code != 422:
        return False
    try:
        # The body is whatever the client managed to parse -- a model, a dict or a string --
        # and one that cannot even be rendered is no evidence of an oversized batch.
        body = str(error.body)
    except Exception:
        return False
    return _TOO_LARGE_MESSAGE in body.lower()


def _count_batches(sizes_MB: List[float]) -> int:
    """How many batches the sizes produce, by the same rule as the batching loop."""
    max_size_MB = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB
    max_length = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE

    batches, length, total_MB = 0, 0, 0.0
    for size_MB in sizes_MB:
        if length == max_length or total_MB + size_MB > max_size_MB:
            batches += 1
            length, total_MB = 1, size_MB
        else:
            length += 1
            total_MB += size_MB
    return batches + 1 if length else max(batches, 1)


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
        try:
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
        except ApiError as exception:
            # The size estimate that built this batch under-read the encoded payload, so send
            # it as halves rather than failing the upload. A single item cannot be split.
            if len(batch) <= 1 or not _is_batch_too_large(exception):
                raise
            LOGGER.warning(
                "Batch of %d experiment items was rejected as too large, retrying it as two halves",
                len(batch),
            )
            half = len(batch) // 2
            self._bulk_upload_batch_with_retry(batch[:half], project_name=project_name)
            self._bulk_upload_batch_with_retry(batch[half:], project_name=project_name)
        else:
            LOGGER.debug(
                "Successfully sent experiment items bulk batch of size %d", len(batch)
            )

    def batch_upload_items(
        self,
        items: Iterable[bulk_item.ExperimentItemBulkRecord],
        project_name: Optional[str] = None,
        num_threads: int = constants.EXPERIMENT_ITEMS_BULK_NUM_THREADS,
        validate_before_upload: bool = True,
    ) -> None:
        """
        Upload experiment items together with their traces, spans and feedback scores.

        Unlike :meth:`insert`, which only links already-existing traces to dataset
        items, this method creates the traces and spans as part of the same request.

        Items are split into batches that respect the backend's 1000-item and
        4MB-per-request limits, and sent with automatic retry on rate limiting
        (HTTP 429).

        Any iterable is accepted, including a generator, and a single-pass source is
        consumed lazily: no item is retained once its batch has been sent, and the
        batches in flight are bounded, so peak memory follows the batch size rather
        than the upload. Passing a list instead holds the whole upload resident before
        the first request goes out, which for large records is the dominant cost --
        286 MiB of records cost 8 MiB from a generator and 306 MiB from a list.

        By default every item is validated before the first batch is sent. That needs a
        second pass over the items, so it applies only to a re-iterable source: from a
        list nothing is sent until every item has passed, and a bad one raises with
        nothing delivered. A generator cannot be walked twice, so each item is validated
        as it is reached instead -- which is what ``validate_before_upload=False`` asks
        for explicitly -- and a later invalid item is found with earlier batches already
        delivered.

        The size that builds a batch is an estimate, so a batch can still be
        rejected as too large. A rejected batch is halved and retried, down to a
        single item, which raises rather than being split further. The halves are
        sent in order, so a batch that fails this way can leave some of its own
        items delivered; the records are converted once, so a retried half
        carries the same ids and duplicates nothing.

        If a batch fails the exception propagates and the experiment is left
        partially populated, but what "remaining" means depends on the worker
        count. With ``num_threads=1`` nothing after the failed batch is sent.
        With the parallel default, batches already in flight are left to finish
        and only those not yet started are dropped, so a few batches after the
        failed one may still have landed. Rate-limit retries
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
            num_threads: Number of batches to upload concurrently. Defaults to
                ``8``; pass ``1`` to upload sequentially, which is the only way
                to guarantee batches arrive in order. Capped at
                ``constants.EXPERIMENT_ITEMS_BULK_MAX_THREADS``, and at the
                number of batches where that is known before the upload starts
                -- from a single-pass source it is not, so the pool is left at
                ``num_threads``.
            validate_before_upload: Whether every item is checked before the
                upload starts, rather than as it goes. Every item is validated
                either way, so this decides when a bad one is reported, not
                whether it is. ``True`` (the default) walks the list once first,
                so a bad item -- failing validation, or too large to fit a
                request on its own -- raises before any request is sent and all
                failures are reported together, at the cost of converting each
                item twice. ``False`` uploads in a single pass and checks each
                item as it is sent, so a bad one raises when it is reached, with
                the batches before it already delivered and no rollback.

        Returns:
            None

        Raises:
            opik.exceptions.ValidationError: If any item fails validation, if a
                single item is too large to fit in one request, if
                ``num_threads`` is less than 1, or if ``validate_before_upload``
                is not a bool.
        """
        if num_threads < 1:
            raise exceptions.ValidationError(
                prefix="batch_upload_items",
                failure_reasons=[f"num_threads must be at least 1, got {num_threads}"],
            )

        # Read as a bare truth value, a non-bool picks a mode instead of being rejected
        # -- "false" asks for the up-front pass it reads as a request to skip.
        if not isinstance(validate_before_upload, bool):
            raise exceptions.ValidationError(
                prefix="batch_upload_items",
                failure_reasons=[
                    "validate_before_upload must be a bool, got "
                    f"{type(validate_before_upload).__name__}"
                ],
            )

        # Re-iterable sources keep the existing contract. A single-pass one cannot be
        # checked up front -- the eager pass would consume it and leave nothing to send --
        # so each item is validated as it is reached instead. Bound as the narrowed
        # sequence rather than a bool, so `len` and the eager pass are reached only where
        # the type says they are available.
        reiterable_items = (
            items if isinstance(items, collections.abc.Sequence) else None
        )
        if reiterable_items is not None and not reiterable_items:
            return

        resolved_project_name = (
            project_name if project_name is not None else self._project_name
        )
        # The backend annotates project_name with @Pattern(NULL_OR_NOT_BLANK), so a
        # blank string is rejected outright rather than falling back to the default
        # project. Treat it as unset, which is what the caller meant.
        if resolved_project_name is not None and not resolved_project_name.strip():
            resolved_project_name = None

        sizes_MB = (
            self._validate_and_size(reiterable_items, resolved_project_name)
            if validate_before_upload and reiterable_items is not None
            else None
        )

        if num_threads == 1:
            for batch in self._stream_rest_batches(
                items, resolved_project_name, sizes_MB
            ):
                self._bulk_upload_batch_with_retry(
                    batch, project_name=resolved_project_name
                )
            return

        # Deliberately not a `with` block: ThreadPoolExecutor.__exit__ always calls
        # shutdown(wait=True), which would re-join batches we just chose not to wait for
        # and park the caller behind a batch stuck in the rate-limit retry loop.
        # More workers than batches is pure waste, and an unbounded caller-supplied value
        # would spawn a thread per batch. The sizes make the count exact; without them
        # the bound has to be an OVER-estimate, because an under-estimate silently caps
        # concurrency -- `ceil(len(items) / 1000)` is 1 for a payload-bound upload of
        # 1,000 large items that actually produces hundreds of batches, which would run
        # the whole thing on one thread. One batch per item is the ceiling.
        if sizes_MB is not None:
            batch_count = _count_batches(sizes_MB)
        elif reiterable_items is not None:
            batch_count = len(reiterable_items)
        else:
            # Unknown until the source is drained, so it bounds nothing; the pool is left
            # to num_threads. More workers than batches is waste, not breakage.
            batch_count = num_threads
        worker_count = max(
            1,
            min(num_threads, batch_count, constants.EXPERIMENT_ITEMS_BULK_MAX_THREADS),
        )
        # Only says a number where one was actually counted. For a single-pass source
        # `batch_count` is the worker bound standing in for a count nobody has, so
        # printing it as "at most N" would report a ceiling the upload does not have.
        if sizes_MB is not None:
            counted = str(batch_count)
        elif reiterable_items is not None:
            counted = f"at most {batch_count}"
        else:
            counted = "an unknown number of"
        LOGGER.debug(
            "Uploading %s experiment items in %s batch(es) using %d thread(s)",
            len(reiterable_items) if reiterable_items is not None else "streamed",
            counted,
            worker_count,
        )
        pool = futures.ThreadPoolExecutor(
            max_workers=worker_count, thread_name_prefix="opik_experiment_items_bulk"
        )
        # Bound the batches alive at once. Without it the producer would run the whole
        # upload into the pool's queue, which is the materialisation this streaming path
        # exists to avoid.
        slots = threading.Semaphore(worker_count * 2)
        first_error: List[BaseException] = []

        def _released(future: "futures.Future") -> None:
            # Record before releasing: a producer blocked in `acquire` wakes on the
            # release, and would pass the `first_error` check and submit one more batch
            # if the failure were not already visible.
            error = future.exception()
            if error is not None and not first_error:
                first_error.append(error)
            slots.release()

        submitted = []
        try:
            for batch in self._stream_rest_batches(
                items, resolved_project_name, sizes_MB
            ):
                # Stop producing once a batch has failed, so a failed upload does not
                # keep sending. The eager path gets this from cancel_futures below.
                if first_error:
                    break
                slots.acquire()
                future = pool.submit(
                    self._bulk_upload_batch_with_retry,
                    batch,
                    project_name=resolved_project_name,
                )
                future.add_done_callback(_released)
                submitted.append(future)
            for future in futures.as_completed(submitted):
                future.result()
        except BaseException:
            # Fail fast: drop batches that have not started and return without joining
            # the ones already in flight.
            pool.shutdown(wait=False, cancel_futures=True)
            raise
        else:
            pool.shutdown(wait=True)

    def _validate_and_size(
        self,
        items: Sequence[bulk_item.ExperimentItemBulkRecord],
        project_name: Optional[str],
    ) -> List[float]:
        """Check every item before anything is sent, and keep the sizes for batching.

        The order matches the eager path: validate the whole list first, then convert and
        size, so a record that fails validation cannot crash the conversion before the
        later failures have been collected. Both loops report every failure together,
        which is the property streaming alone cannot offer.

        Converted records are measured and dropped rather than kept. Retaining them is
        the second full list this streaming path exists to avoid, and it grows with the
        upload, so the conversion is paid again while sending, where it overlaps the
        requests instead of delaying the first one.
        """
        bulk_converters.validate_records(items, project_name=project_name)

        max_size_MB = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB
        sizes_MB: List[float] = []
        failure_reasons: List[str] = []

        for index, item in enumerate(items):
            try:
                size_MB = bulk_converters.payload_size_MB(
                    bulk_converters.to_rest_record(item)
                )
            except bulk_converters.UnmeasurableRecordError as error:
                # Reported as itself. Calling it oversized would be a wrong explanation
                # that reads like a right one, and sends the caller off reducing a
                # record whose size was never the problem.
                sizes_MB.append(float("inf"))
                failure_reasons.append(
                    bulk_converters.unmeasurable_failure_reason(
                        index, error, max_size_MB
                    )
                )
                continue
            sizes_MB.append(size_MB)
            if size_MB >= max_size_MB:
                failure_reasons.append(
                    f"items[{index}] is {size_MB:.1f}MB, which is at or above the "
                    f"{max_size_MB}MB per-request limit"
                )

        if failure_reasons:
            raise exceptions.ValidationError(
                prefix="batch_upload_items", failure_reasons=failure_reasons
            )

        return sizes_MB

    def _stream_rest_batches(
        self,
        items: Iterable[bulk_item.ExperimentItemBulkRecord],
        project_name: Optional[str],
        sizes_MB: Optional[List[float]] = None,
    ) -> Iterator[
        List[rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView]
    ]:
        """Convert and batch in one pass, yielding each batch as it fills.

        The eager path makes four sequential passes over the whole upload -- validate,
        convert, size every item to reject oversized ones, then size every item again to
        batch them -- and only then sends. The two sizing passes are the same
        computation, and all four complete before the first request leaves, so no send
        thread overlaps any of them.

        ``sizes_MB`` is what :meth:`_validate_and_size` already measured. When it is
        absent nothing has been checked yet, so each item is validated and sized here and
        a bad one raises when it is reached, with earlier batches already delivered.

        Batch boundaries are identical to ``split_into_batches`` either way, for input
        that has no oversized item -- which is the only input either path accepts.

        ``items`` must not be mutated while this runs. Records are converted here a
        second time rather than carried over from the sizing pass, because retaining
        them is the memory this path exists not to spend -- so a size measured there
        describes the record as it was then. Nothing is copied on the way through, and
        this is a generator driven by the sending loop, so a mutation applied from
        another thread mid-upload lands in the record that gets sent while the size
        stays behind. Sizing here instead would close that, at a cost that rises with
        payload size: conversion is flat per record while sizing scales with bytes, so
        the heavier the upload the worse the trade, and that CPU is what this path
        exists to remove.
        """
        max_size_MB = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB
        max_length = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE

        batch: List[
            rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView
        ] = []
        batch_size_MB = 0.0

        for index, item in enumerate(items):
            if sizes_MB is None:
                bulk_converters.validate_record(item, index, project_name)
            rest_item = bulk_converters.to_rest_record(item)
            if sizes_MB is not None:
                size_MB = sizes_MB[index]
            else:
                try:
                    size_MB = bulk_converters.payload_size_MB(rest_item)
                except bulk_converters.UnmeasurableRecordError as error:
                    raise exceptions.ValidationError(
                        prefix="batch_upload_items",
                        failure_reasons=[
                            bulk_converters.unmeasurable_failure_reason(
                                index, error, max_size_MB
                            )
                        ],
                    ) from error

                if size_MB >= max_size_MB:
                    raise exceptions.ValidationError(
                        prefix="batch_upload_items",
                        failure_reasons=[
                            f"items[{index}] is {size_MB:.1f}MB, which is at or above "
                            f"the {max_size_MB}MB per-request limit"
                        ],
                    )

            if len(batch) == max_length or batch_size_MB + size_MB > max_size_MB:
                yield batch
                batch, batch_size_MB = [rest_item], size_MB
            else:
                batch.append(rest_item)
                batch_size_MB += size_MB

        if batch:
            yield batch

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
