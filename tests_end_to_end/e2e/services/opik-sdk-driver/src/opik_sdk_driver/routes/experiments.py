import atexit

import opik
from fastapi import APIRouter, Header
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals

from ..opik_factory import make_opik_client
from ..schemas import (
    CompareExperimentResult,
    ExperimentCompareSeedRequest,
    ExperimentCompareSeedResponse,
    ExperimentEvaluateRequest,
    ExperimentEvaluateResponse,
    ExperimentItemFingerprint,
    ExperimentItemScore,
    ExperimentReadItemsRequest,
    ExperimentReadItemsResponse,
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
    "/read-items",
    response_model=ExperimentReadItemsResponse,
    status_code=200,
)
def read_experiment_items(
    body: ExperimentReadItemsRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentReadItemsResponse:
    """Read an experiment's items back through `Experiment.get_items()`.

    The estate writes experiment items (through the TS backend client) but has
    never read them back through the Python SDK, which is the path OPIK-8274
    rewrote into concurrent 2,000-item waves over raw JSON.

    Only the knobs the caller actually set are forwarded, so an omitted
    `page_size`/`num_threads`/`max_results` exercises the SDK's own default
    rather than a copy of it pinned here — which would quietly stop testing the
    default the day it changed.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        experiment = client.get_experiment_by_id(body.experiment_id)

        kwargs: dict[str, int] = {}
        if body.max_results is not None:
            kwargs["max_results"] = body.max_results
        if body.page_size is not None:
            kwargs["page_size"] = body.page_size
        if body.num_threads is not None:
            kwargs["num_threads"] = body.num_threads

        items = experiment.get_items(**kwargs)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    fingerprints: list[ExperimentItemFingerprint] = []
    for item in items:
        data = item.dataset_item_data or {}
        raw_idx = data.get("idx")
        fingerprints.append(
            ExperimentItemFingerprint(
                id=str(item.id),
                dataset_item_id=str(item.dataset_item_id),
                trace_id=str(item.trace_id),
                # Only a genuine integer counts. A missing or non-numeric idx
                # comes back as None so the caller's contiguity assertion
                # fails, rather than being coerced into a plausible number.
                idx=raw_idx
                if isinstance(raw_idx, int) and not isinstance(raw_idx, bool)
                else None,
            )
        )

    return ExperimentReadItemsResponse(
        experiment_id=body.experiment_id,
        count=len(fingerprints),
        items=fingerprints,
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
