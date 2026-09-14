import atexit
import contextlib
import datetime
import logging
from typing import Iterator, List, Optional, Tuple

import opik
from fastapi import APIRouter, Header
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals

from ..opik_factory import make_opik_client
from ..schemas import (
    CompareExperimentResult,
    ExperimentBulkUploadItem,
    ExperimentBulkUploadRequest,
    ExperimentBulkUploadResponse,
    ExperimentCompareSeedRequest,
    ExperimentCompareSeedResponse,
    ExperimentEvaluateRequest,
    ExperimentEvaluateResponse,
    ExperimentItemScore,
)

router = APIRouter(prefix="/experiments", tags=["experiments"])


_SCORE_METRIC_NAME = "equals_metric"


def _collect_scores(result) -> list[ExperimentItemScore]:
    scores: list[ExperimentItemScore] = []
    for tr in result.test_results:
        for sr in tr.score_results:
            if sr.name != _SCORE_METRIC_NAME:
                continue
            item_content = tr.test_case.dataset_item_content or {}
            scores.append(
                ExperimentItemScore(
                    dataset_item_id=str(tr.test_case.dataset_item_id),
                    input=str(item_content.get("input", "")),
                    expected_output=str(item_content.get("expected_output", "")),
                    task_output=str(item_content.get("task_output", "")),
                    score_name=sr.name,
                    score_value=float(sr.value),
                )
            )
    return scores


@router.post("/evaluate", response_model=ExperimentEvaluateResponse, status_code=201)
def evaluate_experiment(
    body: ExperimentEvaluateRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentEvaluateResponse:
    """Seed dataset + run deterministic-evaluator evaluate in one shot.

    The task is a no-op echo that returns the item's `task_output` field,
    so the seed shape controls each row's pass/fail outcome. Scoring uses
    Equals(case_sensitive=False) keyed on output vs expected_output.
    """
    # evaluate() calls opik_client.get_global_client() internally and ignores
    # any locally-constructed client. Bind the request-scoped client as the
    # global so the auth/workspace context propagates into the evaluate path.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)
    try:
        dataset = client.create_dataset(
            name=body.dataset_name,
            description=body.dataset_description,
            project_name=body.project_name,
        )
        dataset.insert([item.model_dump() for item in body.items])

        def _task(item: dict) -> dict:
            return {"output": item["task_output"]}

        result = evaluate(
            dataset=dataset,
            task=_task,
            scoring_metrics=[Equals(case_sensitive=False)],
            experiment_name=body.experiment_name,
            project_name=body.project_name,
            task_threads=1,
            verbose=0,
            scoring_key_mapping={"reference": "expected_output"},
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    scores = _collect_scores(result)

    return ExperimentEvaluateResponse(
        experiment_id=str(result.experiment_id),
        experiment_name=result.experiment_name or body.experiment_name,
        dataset_id=str(result.dataset_id),
        item_count=len(body.items),
        scored_item_count=len(result.test_results),
        scores=scores,
    )


@router.post(
    "/compare-seed",
    response_model=ExperimentCompareSeedResponse,
    status_code=201,
)
def compare_seed(
    body: ExperimentCompareSeedRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentCompareSeedResponse:
    """Seed one dataset and run N experiments over its *shared* items.

    Unlike /evaluate, task_output is NOT stored on the dataset item. The items
    carry only input + expected_output, so every experiment runs against the
    same dataset-item ids (same content hash). Each experiment supplies its own
    task_outputs (aligned by index with body.items), so the Equals scores can
    diverge per experiment while the compare view still aligns rows by item.
    """
    if any(len(exp.task_outputs) != len(body.items) for exp in body.experiments):
        raise ValueError("each experiment's task_outputs must align 1:1 with items")

    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)
    try:
        dataset = client.create_dataset(
            name=body.dataset_name,
            description=body.dataset_description,
            project_name=body.project_name,
        )
        dataset.insert([item.model_dump() for item in body.items])

        results: list[CompareExperimentResult] = []
        for exp in body.experiments:
            # Map input -> task_output for this experiment. The task receives a
            # dataset item (input + expected_output) and echoes the mapped
            # output, so scoring is Equals(mapped_output, expected_output).
            output_by_input = {
                item.input: task_output
                for item, task_output in zip(body.items, exp.task_outputs)
            }

            def _task(item: dict, _map=output_by_input) -> dict:
                return {"output": _map[item["input"]]}

            result = evaluate(
                dataset=dataset,
                task=_task,
                scoring_metrics=[Equals(case_sensitive=False)],
                experiment_name=exp.experiment_name,
                project_name=body.project_name,
                task_threads=1,
                verbose=0,
                scoring_key_mapping={"reference": "expected_output"},
            )
            results.append(
                CompareExperimentResult(
                    experiment_id=str(result.experiment_id),
                    experiment_name=result.experiment_name or exp.experiment_name,
                    scores=_collect_scores(result),
                )
            )
        dataset_id = str(dataset.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return ExperimentCompareSeedResponse(
        dataset_id=dataset_id,
        dataset_name=body.dataset_name,
        item_count=len(body.items),
        experiments=results,
    )


# The module whose LOGGER states how `batch_upload_items` split the work.
_BULK_UPLOAD_LOGGER_NAME = "opik.api_objects.experiment.experiment"


class _BulkUploadObserver(logging.Handler):
    """Reads the batch and thread counts off the SDK's own debug log.

    `batch_upload_items` splits its records against a payload-size ceiling and
    then submits the batches to a pool, but it returns None and exposes neither
    number. The alternative ways to learn them are all worse: recomputing the
    split here would assert this route's arithmetic rather than the SDK's, and
    spying on the REST client means reaching into `opik.rest_api` internals the
    bridge is not allowed to depend on. The SDK already states both, once per
    call, in `LOGGER.debug("Uploading %d experiment items in %d batch(es) using
    %d thread(s)")` — so this reads that.

    Nothing is inferred when the line does not arrive. The counts stay None and
    the caller is told so, which fails an assertion about the fan-out instead of
    quietly satisfying one.
    """

    def __init__(self) -> None:
        super().__init__(level=logging.DEBUG)
        self.batch_count: Optional[int] = None
        self.num_threads: Optional[int] = None

    def emit(self, record: logging.LogRecord) -> None:
        args = record.args
        if not isinstance(args, tuple) or len(args) != 3:
            return
        if "batch(es)" not in str(record.msg):
            return
        _, batch_count, num_threads = args
        self.batch_count = int(batch_count)
        self.num_threads = int(num_threads)


@contextlib.contextmanager
def _observe_bulk_upload() -> Iterator[_BulkUploadObserver]:
    observer = _BulkUploadObserver()
    logger = logging.getLogger(_BULK_UPLOAD_LOGGER_NAME)
    previous_level = logger.level
    logger.setLevel(logging.DEBUG)
    logger.addHandler(observer)
    try:
        yield observer
    finally:
        logger.removeHandler(observer)
        logger.setLevel(previous_level)


def _bulk_records(
    dataset_item_ids: List[str],
    experiment_name: str,
    score_name: str,
    filler_bytes: int,
) -> Tuple[List[opik.ExperimentItemBulkRecord], List[ExperimentBulkUploadItem]]:
    """One record per dataset item, each carrying a trace, a span and a score.

    Scores cycle 0.0, 0.1 … 0.9 by index, so the mean over any whole number of
    ten items is exactly 0.45 — a number a dropped or duplicated batch moves,
    unlike a count, which a duplicate and a drop can cancel out of.

    Timestamps are fixed for the whole upload rather than taken per record: the
    trace window is not what is under test, and a moving `now` would make the
    serialized size of a record depend on when it was built.
    """
    filler = "x" * filler_bytes
    start_time = datetime.datetime.now(datetime.timezone.utc)
    end_time = start_time + datetime.timedelta(seconds=1)

    records: List[opik.ExperimentItemBulkRecord] = []
    uploaded: List[ExperimentBulkUploadItem] = []
    for index, dataset_item_id in enumerate(dataset_item_ids):
        score = (index % 10) / 10
        records.append(
            opik.ExperimentItemBulkRecord(
                dataset_item_id=dataset_item_id,
                trace=opik.ExperimentItemBulkTrace(
                    start_time=start_time,
                    end_time=end_time,
                    name=f"{experiment_name}-{index}",
                    input={"index": index, "filler": filler},
                    output={"index": index},
                ),
                spans=[
                    opik.ExperimentItemBulkSpan(
                        start_time=start_time,
                        end_time=end_time,
                        name="bulk_task",
                        type="general",
                        input={"index": index},
                        output={"index": index},
                    )
                ],
                feedback_scores=[{"name": score_name, "value": score}],
            )
        )
        uploaded.append(
            ExperimentBulkUploadItem(dataset_item_id=dataset_item_id, score=score)
        )
    return records, uploaded


@router.post(
    "/bulk-upload",
    response_model=ExperimentBulkUploadResponse,
    status_code=201,
)
def bulk_upload_experiment_items(
    body: ExperimentBulkUploadRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentBulkUploadResponse:
    """One `Experiment.batch_upload_items(...)` over a whole dataset.

    Unlike /evaluate and /compare-seed, which reach the bulk path indirectly
    through `evaluate()`, this drives `batch_upload_items` itself — the entry
    point an SDK caller uses to upload results computed elsewhere, and the one
    whose worker count changed in opik#8315.

    The experiment is created in the dataset's own project on purpose: the bulk
    endpoint answers 409 when the upload's project_name differs from the
    dataset's, so two experiments over one shared dataset can only be compared
    when both live there.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        dataset = client.get_dataset(
            name=body.dataset_name, project_name=body.project_name
        )
        dataset_item_ids = [str(item["id"]) for item in dataset.get_items()]
        records, uploaded = _bulk_records(
            dataset_item_ids,
            experiment_name=body.experiment_name,
            score_name=body.score_name,
            filler_bytes=body.filler_bytes,
        )

        experiment = client.create_experiment(
            dataset_name=body.dataset_name,
            name=body.experiment_name,
            project_name=body.project_name,
        )
        # Omitted rather than defaulted: passing the SDK's own default back to
        # it would look identical here and prove nothing about what a caller who
        # passes nothing actually gets.
        kwargs = {} if body.num_threads is None else {"num_threads": body.num_threads}
        with _observe_bulk_upload() as observed:
            experiment.batch_upload_items(records, **kwargs)
        experiment_id = str(experiment.id)
        experiment_name = experiment.name
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return ExperimentBulkUploadResponse(
        experiment_id=experiment_id,
        experiment_name=experiment_name,
        record_count=len(records),
        batch_count=observed.batch_count,
        num_threads=observed.num_threads,
        items=uploaded,
    )
