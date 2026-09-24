import collections.abc
import functools
import logging
import threading
from concurrent import futures
from typing import (
    Dict,
    Iterable,
    Iterator,
    List,
    NamedTuple,
    Optional,
    Sequence,
    Tuple,
    TYPE_CHECKING,
)

import httpx

from opik.message_processing.batching import sequence_splitter
from opik.message_processing import messages, streamer
from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from . import bulk_converters, bulk_item, experiment_item, experiments_client
from .. import constants, helpers, rest_helpers, streaming_upload, validation_helpers
from ...api_objects.prompt import base_prompt
from ...rest_api.core.api_error import ApiError
from ...rest_client_configurator import retry_decorator
from ... import config, exceptions, httpx_client

if TYPE_CHECKING:
    from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)

_BULK_PATH = "v1/private/experiments/items/bulk"

# Closes the item array and the envelope the prefix opened.
_BULK_SUFFIX = b"]}"

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


class _BulkUpload(NamedTuple):
    """Everything one upload needs to turn serialised records into requests.

    Resolved once per call rather than per batch: the envelope is the same for every
    request, and `Experiment.name` behind it can cost a round trip the first time it is
    read.
    """

    client: httpx.Client
    base_url: str
    headers: Dict[str, str]
    #: The envelope up to and including `"items":[`, for the item fragments to follow.
    prefix: bytes
    #: None sends the body uncompressed, for a client configured with compression off.
    gzip_level: Optional[int]
    #: Set when the upload is aborted, so no send starts another request after it.
    stop_event: threading.Event


def _batch_chunks(
    upload: _BulkUpload, payloads: List[bytes]
) -> Tuple[List[bytes], int]:
    """The pieces of one request body, and the size of the body they make.

    Pieces rather than one buffer, so the comma between two records never copies the
    record before it, and so the gzip below can take them a slice at a time. The size is
    counted here because it is what the send pool budgets in, and summing it afterwards
    would walk the pieces a second time.
    """
    chunks: List[bytes] = [upload.prefix]
    body_bytes = len(upload.prefix) + len(_BULK_SUFFIX)
    for index, payload in enumerate(payloads):
        if index:
            chunks.append(b",")
            body_bytes += 1
        chunks.append(payload)
        body_bytes += len(payload)
    chunks.append(_BULK_SUFFIX)
    return chunks, body_bytes


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

    def _open_bulk_upload(self, project_name: Optional[str]) -> _BulkUpload:
        """The transport and envelope every batch of this upload is sent through.

        The transport is resolved by the same helper the dataset upload uses, so both
        paths reach it the same way. Auth and workspace live on the generated client's
        wrapper instead of on the transport when the REST client was built directly,
        which is what `wrapper_headers` recovers; without it such a client would upload
        unauthenticated.

        The envelope is serialised by the same encoder as the records, so the experiment
        and dataset names are escaped the same way, and spliced open ready for the item
        fragments. Its keys are the ones the generated client sent, `project_name`
        included as an explicit null when there is none -- the backend reads absent and
        null differently, so dropping it is not the same request.
        """
        client, base_url = httpx_client.upload_transport(self._rest_client)

        envelope = streaming_upload.dumps(
            {
                "experiment_name": self.name,
                "dataset_name": self.dataset_name,
                "experiment_id": self.id,
                "project_name": project_name,
            }
        )

        opik_config = config.OpikConfig()
        # The enable flag gates the level: a client built with compression off must not be
        # handed gzipped bodies, whatever level is configured. A transport carrying no
        # setting of its own -- a REST client built directly sends through a plain httpx
        # client -- takes the configured one.
        compressing = httpx_client.compresses_json_requests(
            client, default=opik_config.enable_json_request_compression
        )
        return _BulkUpload(
            client=client,
            base_url=base_url,
            headers=httpx_client.wrapper_headers(self._rest_client),
            prefix=envelope[:-1] + b',"items":[',
            gzip_level=(
                opik_config.experiment_upload_compression_level if compressing else None
            ),
            stop_event=threading.Event(),
        )

    def _send_prepared_body(self, upload: _BulkUpload, body: bytes) -> None:
        """Send one already-serialised request body."""

        def send() -> None:
            # Checked per attempt, so the REST retry below starts no request after an abort.
            if upload.stop_event.is_set():
                raise futures.CancelledError("experiment items bulk upload aborted")
            response = httpx_client.send_prepared_json(
                upload.client,
                upload.base_url,
                _BULK_PATH,
                body,
                headers=upload.headers,
            )
            if response.status_code >= 300:
                raise ApiError(
                    status_code=response.status_code,
                    headers=dict(response.headers),
                    body=response.text,
                )

        # `rest_client_configurator` wraps every generated client method in this retry, so
        # a body sent through the raw sender has to carry it too or the bulk path would be
        # the one path that gives up on a transient 5xx. Nested as it is there: retries
        # inside, the rate-limit wait outside.
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            retry_decorator.opik_rest_retry(send),
            operation_name="experiment_items_bulk",
            stop_event=upload.stop_event,
        )

    def _send_batch(self, upload: _BulkUpload, body: bytes, batch: List[bytes]) -> None:
        """Send one prepared body, halving the batch if the server rejects its size.

        Runs on the sending worker, which has already compressed `body` -- zlib releases
        the GIL, so compression is the one part of an upload that parallelises across
        workers, and doing it on the producer would funnel all of it through one thread.
        `send_prepared_json` bypasses `OpikHttpxClient.build_request`, so the automatic
        compression does not apply and the pool does it instead.

        `batch` is the record fragments the body was built from, carried through the pool
        because the bytes alone cannot be split back up. A half re-joins the same
        fragments, so every id in it is the one already sent -- nothing is re-converted
        and nothing can be minted twice.
        """
        try:
            self._send_prepared_body(upload, body)
        except ApiError as exception:
            # This deployment caps a request below our own cap, so send it as halves
            # rather than failing the upload. A single item cannot be split.
            if len(batch) <= 1 or not _is_batch_too_large(exception):
                raise
            LOGGER.warning(
                "Batch of %d experiment items was rejected as too large, retrying it as two halves",
                len(batch),
            )
            half = len(batch) // 2
            for part in (batch[:half], batch[half:]):
                # Compressed here rather than back through the pool: this already runs on
                # a worker, and re-submitting from one would wait on the pool that is
                # waiting on it.
                chunks, _ = _batch_chunks(upload, part)
                self._send_batch(
                    upload,
                    streaming_upload.encode_body(chunks, upload.gzip_level),
                    part,
                )
        else:
            LOGGER.debug(
                "Successfully sent experiment items bulk batch of size %d", len(batch)
            )

    def _upload_batches(
        self,
        upload: _BulkUpload,
        items: Iterable[bulk_item.ExperimentItemBulkRecord],
        project_name: Optional[str],
        sizes_MB: Optional[List[float]],
        worker_count: int,
    ) -> None:
        """Stream the batches into the bounded send pool shared with the dataset upload.

        Same bound, same compress-on-the-worker rule, and at ``worker_count`` of 1 the
        same inline send. ``fail_fast`` is where the two paths differ: a dataset upload
        drains what it has queued, this one drops whatever has not started.
        """
        pool = streaming_upload.BoundedSendPool(
            send=functools.partial(self._send_batch, upload),
            num_threads=worker_count,
            gzip_level=upload.gzip_level,
            fail_fast=True,
            thread_name_prefix="opik_experiment_items_bulk",
            stop_event=upload.stop_event,
        )
        try:
            for batch in self._stream_rest_batches(items, project_name, sizes_MB):
                chunks, body_bytes = _batch_chunks(upload, batch)
                pool.submit(chunks, len(batch), body_bytes, payload=batch)
        except BaseException:
            pool.abort()
            raise
        pool.close()

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

        A batch is built from the byte count of the records it will send, but a
        deployment may cap a request below the SDK's own cap, so one can still be
        rejected as too large. A rejected batch is halved and retried, down to a
        single item, which raises rather than being split further. The halves are
        sent in order, so a batch that fails this way can leave some of its own
        items delivered; a half re-sends the bytes already serialised, so it
        carries the same ids and duplicates nothing.

        If a batch fails the exception propagates and the experiment is left
        partially populated, but what "remaining" means depends on the worker
        count. With ``num_threads=1`` nothing after the failed batch is sent.
        With the parallel default, batches not yet started are dropped and those
        already started are told to stop: one waiting out a rate limit or a retry
        gives up without sending again, but a request already on the wire cannot
        be interrupted, so a few batches after the failed one may still land. The
        call waits up to a few seconds for started batches to stop, then returns
        and logs a warning for any still running in the background. Rate-limit retries
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

        upload = self._open_bulk_upload(resolved_project_name)

        if num_threads == 1:
            # One worker is no pool at all: each body is compressed and sent inline, in
            # order, on this thread.
            self._upload_batches(upload, items, resolved_project_name, sizes_MB, 1)
            return

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
        self._upload_batches(
            upload, items, resolved_project_name, sizes_MB, worker_count
        )

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
    ) -> Iterator[List[bytes]]:
        """Convert, serialise and batch in one pass, yielding each batch as it fills.

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

        Each record is serialised once, here, and what a batch carries is those bytes.
        The length of a fragment is the size it is budgeted at, so the number that closes
        a batch is the number of bytes that batch will send rather than a prediction of
        it -- and a record mutated from another thread after it was sized can no longer
        be sent in a form the size does not describe. The sizes from the eager pass are
        the same measurement, so they are used only to say that validation has already
        run.

        ``items`` must not be mutated while this runs. Records are converted here a
        second time rather than carried over from the sizing pass, because retaining
        them is the memory this path exists not to spend.
        """
        max_size_MB = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB
        max_length = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE

        batch: List[bytes] = []
        batch_size_MB = 0.0

        for index, item in enumerate(items):
            if sizes_MB is None:
                bulk_converters.validate_record(item, index, project_name)
            rest_item = bulk_converters.to_rest_record(item)
            try:
                payload = bulk_converters.serialize_record(rest_item)
            except bulk_converters.UnmeasurableRecordError as error:
                # Refused rather than degraded, and refused here because this is where
                # the item's index is still known. The eager pass reaches the same
                # verdict for a re-iterable source, before anything is sent.
                raise exceptions.ValidationError(
                    prefix="batch_upload_items",
                    failure_reasons=[
                        bulk_converters.unmeasurable_failure_reason(
                            index, error, max_size_MB
                        )
                    ],
                ) from error
            size_MB = bulk_converters.size_MB(payload)

            if sizes_MB is None and size_MB >= max_size_MB:
                raise exceptions.ValidationError(
                    prefix="batch_upload_items",
                    failure_reasons=[
                        f"items[{index}] is {size_MB:.1f}MB, which is at or above "
                        f"the {max_size_MB}MB per-request limit"
                    ],
                )

            if len(batch) == max_length or batch_size_MB + size_MB > max_size_MB:
                yield batch
                batch, batch_size_MB = [payload], size_MB
            else:
                batch.append(payload)
                batch_size_MB += size_MB

        if batch:
            yield batch

    def get_items(
        self,
        max_results: Optional[int] = 10000,
        truncate: bool = False,
        *,
        page_size: int = constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE,
        num_threads: int = constants.DATASET_ITEMS_READ_NUM_THREADS,
    ) -> List[experiment_item.ExperimentItemContent]:
        """
        Retrieves and returns a list of experiment items for this experiment.

        Args:
            max_results: Maximum number of experiment items to retrieve. Defaults to 10000 if not specified.
            truncate: Whether to truncate the items returned by the backend. Defaults to False.
            page_size: Number of dataset items requested per page. Must be a
                positive integer not exceeding
                ``constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE``. The read is
                round-trip bound, so this mostly trades request count against
                per-request size; lower it only if the backend struggles with
                the default response size.
            num_threads: Number of pages fetched concurrently after the first
                one, which is read on its own to learn how many pages there are.
                Must be a positive integer not exceeding
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``. Pass ``1`` to read
                sequentially.

        Returns:
            List of ExperimentItemContent objects for this experiment.

        Raises:
            ValueError: If ``page_size`` is not a positive integer or exceeds
                ``constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE``, or if
                ``num_threads`` is not a positive integer or exceeds
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``.
        """
        validation_helpers.validate_bounded_positive_int(
            page_size, "page_size", constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
        )
        validation_helpers.validate_bounded_positive_int(
            num_threads, "num_threads", constants.DATASET_ITEMS_READ_MAX_THREADS
        )

        if max_results is None:
            max_results = 10000  # TODO: remove this once we have a proper way to get all experiment items

        return self._experiments_client.find_experiment_items_for_dataset(
            dataset_name=self.dataset_name,
            experiment_ids=[self.id],
            truncate=truncate,
            max_results=max_results,
            project_name=self._project_name,
            page_size=page_size,
            num_threads=num_threads,
        )

    def log_experiment_scores(
        self,
        score_results: List["score_result.ScoreResult"],
        *,
        preserve_unrelated: bool = False,
    ) -> List["score_result.ScoreResult"]:
        """Log scores and return effective scores, replacing recomputed names.

        ``preserve_unrelated`` retains persisted names not recomputed (default false); failed supplied scores return but are not persisted.
        """
        if not score_results:
            return []

        experiment_scores_map: Dict[str, rest_api_types.ExperimentScore] = {}
        effective_scores: List["score_result.ScoreResult"] = []
        recomputed_names = {score_result_.name for score_result_ in score_results}

        if preserve_unrelated:
            from opik.evaluation.metrics import score_result as score_result_module

            existing_experiment = self.get_experiment_data()
            existing_scores = existing_experiment.experiment_scores or []
            for score in existing_scores:
                if score.name not in recomputed_names:
                    experiment_scores_map[score.name] = rest_api_types.ExperimentScore(
                        name=score.name, value=score.value
                    )
                    effective_scores.append(
                        score_result_module.ScoreResult(
                            name=score.name, value=score.value
                        )
                    )

        for score_result_ in score_results:
            effective_scores.append(score_result_)
            if score_result_.scoring_failed:
                experiment_scores_map.pop(score_result_.name, None)
                continue

            experiment_scores_map[score_result_.name] = rest_api_types.ExperimentScore(
                name=score_result_.name,
                value=score_result_.value,
            )

        experiment_scores = list(experiment_scores_map.values())
        # The update is a full replacement, so writing an empty list would erase
        # aggregates this call never looked at.
        if not experiment_scores and not preserve_unrelated:
            return effective_scores

        self._rest_client.experiments.update_experiment(
            id=self.id,
            experiment_scores=experiment_scores,
        )

        return effective_scores
