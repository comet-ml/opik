import atexit

import opik
from fastapi import APIRouter, Header
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals
from opik.evaluation.metrics.heuristics.readability import Readability

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
    ReadabilityEvaluateRequest,
    ReadabilityEvaluateResponse,
    ReadabilityLocaleScore,
)

router = APIRouter(prefix="/experiments", tags=["experiments"])


_SCORE_METRIC_NAME = "equals_metric"


def _readability_metric_name(language: str) -> str:
    """Per-locale metric name.

    `Readability` defaults every instance to `readability_metric`, so four
    locales in one run would write four scores under one name and the last
    writer would win. Naming them apart is what makes the per-locale score
    addressable — in the response here, and as its own column in the compare
    grid.
    """
    return f"readability_{language}"


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
    "/readability-evaluate",
    response_model=ReadabilityEvaluateResponse,
    status_code=201,
)
def readability_evaluate(
    body: ReadabilityEvaluateRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ReadabilityEvaluateResponse:
    """Score one dataset with several `Readability` locales in ONE evaluate run.

    This is the only shape that reaches what opik#8318 changed. `textstat`
    carries its locale in MODULE state, set through `set_lang`, and the
    evaluation engine scores metrics from a thread pool — so N locales in one
    run is exactly the interleaving the new module-wide lock exists to
    serialise. Scoring them in N separate runs would pass just as happily with
    the lock removed.

    Each language is also scored SEQUENTIALLY here, outside `evaluate()`, and
    returned alongside as `serial_value`. Comparing the two is what separates
    the two ways this can regress: if `set_lang` stops being applied at all the
    languages stop differing from each other, and if the lock stops holding
    they differ from their own uncontended value.

    Both halves are computed in this one process, so they share a textstat
    version and the comparison never depends on an absolute Flesch number.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    # The uncontended reference, taken before the concurrent run and one metric
    # at a time. `track=False` because these are a pure computation for
    # comparison, not part of the experiment being seeded.
    serial_values: dict[tuple[str, str], float] = {
        (item.key, language): float(
            Readability(language=language, track=False).score(output=item.text).value
        )
        for item in body.items
        for language in body.languages
    }

    language_by_metric_name = {
        _readability_metric_name(language): language for language in body.languages
    }

    try:
        dataset = client.create_dataset(
            name=body.dataset_name,
            description=body.dataset_description,
            project_name=body.project_name,
        )
        dataset.insert(
            [{"key": item.key, "input": item.text, "task_output": item.text} for item in body.items]
        )

        def _task(item: dict) -> dict:
            return {"output": item["task_output"]}

        result = evaluate(
            dataset=dataset,
            task=_task,
            scoring_metrics=[
                Readability(name=_readability_metric_name(language), language=language)
                for language in body.languages
            ],
            experiment_name=body.experiment_name,
            project_name=body.project_name,
            task_threads=body.task_threads,
            verbose=0,
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    scores: list[ReadabilityLocaleScore] = []
    for test_result in result.test_results:
        item_content = test_result.test_case.dataset_item_content or {}
        key = str(item_content.get("key", ""))
        for score_result in test_result.score_results:
            language = language_by_metric_name.get(score_result.name)
            if language is None:
                continue
            scores.append(
                ReadabilityLocaleScore(
                    dataset_item_id=str(test_result.test_case.dataset_item_id),
                    key=key,
                    language=language,
                    metric_name=score_result.name,
                    evaluated_value=float(score_result.value),
                    # KeyError rather than a default: a score whose (key,
                    # language) pair was never seeded is a bug in this route,
                    # and substituting a plausible number would hide it.
                    serial_value=serial_values[(key, language)],
                    scoring_failed=bool(score_result.scoring_failed),
                )
            )

    return ReadabilityEvaluateResponse(
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
