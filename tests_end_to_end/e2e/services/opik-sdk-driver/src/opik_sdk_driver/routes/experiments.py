import atexit

import opik
from fastapi import APIRouter, Header
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals, base_metric, score_result
from opik.evaluation.types import ErrorTolerance

from ..opik_factory import make_opik_client
from ..schemas import (
    AbortingRunResult,
    CompareExperimentResult,
    ExperimentCompareSeedRequest,
    ExperimentCompareSeedResponse,
    ExperimentEvaluateRequest,
    ExperimentEvaluateResponse,
    ExperimentItemScore,
    ExperimentScoringErrorSeedRequest,
    ExperimentScoringErrorSeedResponse,
    ScoringErrorItemResult,
    ToleratedRunResult,
)

router = APIRouter(prefix="/experiments", tags=["experiments"])


_SCORE_METRIC_NAME = "equals_metric"

_PASSING_METRIC_NAME = "no_kwargs_exact_match"
_FAILING_METRIC_NAME = "needs_context"


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


class _NoKwargsExactMatch(base_metric.BaseMetric):
    """Exact match whose score() signature ends WITHOUT **ignored_kwargs.

    This is the point of the metric. The shipped `Equals` absorbs every offered
    key through **ignored_kwargs, so it never enters the argument-narrowing
    branch in select_score_arguments. Declaring only the two arguments it uses
    forces that branch to run for real.
    """

    def __init__(self) -> None:
        super().__init__(name=_PASSING_METRIC_NAME)

    def score(self, output: str, reference: str) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0 if output == reference else 0.0
        )


class _NeedsContext(base_metric.BaseMetric):
    """Requires a `context` argument no dataset item and no task output supplies.

    Scoring can therefore never run, and the engine must raise
    ScoreMethodMissingArguments — aborting the whole evaluation at the default
    error tolerance, or recording a per-item scoring failure under
    ALL_SCORING_ERRORS. The body is unreachable.
    """

    def __init__(self) -> None:
        super().__init__(name=_FAILING_METRIC_NAME)

    def score(self, output: str, context: str) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=1.0)


def _scoring_error_item_results(result) -> list[ScoringErrorItemResult]:
    out: list[ScoringErrorItemResult] = []
    for tr in result.test_results:
        trace_id = getattr(tr.test_case, "trace_id", None)
        for sr in tr.score_results:
            error_info = (sr.metadata or {}).get("error_info") or {}
            out.append(
                ScoringErrorItemResult(
                    dataset_item_id=str(tr.test_case.dataset_item_id),
                    trace_id=str(trace_id) if trace_id else None,
                    metric_name=sr.name,
                    value=float(sr.value),
                    scoring_failed=bool(sr.scoring_failed),
                    error_exception_type=error_info.get("exception_type"),
                )
            )
    return out


@router.post(
    "/scoring-error-seed",
    response_model=ExperimentScoringErrorSeedResponse,
    status_code=201,
)
def scoring_error_seed(
    body: ExperimentScoringErrorSeedRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentScoringErrorSeedResponse:
    """Evaluate one dataset twice with a passing + a permanently-failing metric.

    Run 1 uses the default error tolerance and is expected to abort with
    ScoreMethodMissingArguments. Run 2 uses ErrorTolerance.ALL_SCORING_ERRORS
    and is expected to complete, recording the failure per item while keeping
    the failing metric out of the aggregate.

    Both runs share one dataset so the only difference between them is the
    tolerance. Neither metric calls an LLM, so the outcome is deterministic.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)
    try:
        dataset = client.create_dataset(
            name=body.dataset_name,
            description=body.dataset_description,
            project_name=body.project_name,
        )
        dataset.insert([item.model_dump() for item in body.items])
        dataset_id = str(dataset.id)

        def _task(item: dict) -> dict:
            return {"output": item["task_output"]}

        def _evaluate(experiment_name: str, error_tolerance: ErrorTolerance | None):
            # error_tolerance is omitted entirely for the aborting run, so that
            # run exercises evaluate()'s *shipped default* rather than a value
            # this bridge restates. If the default is ever widened to tolerate
            # scoring errors, the caller's abort assertion is what catches it.
            tolerance_kwargs = (
                {} if error_tolerance is None else {"error_tolerance": error_tolerance}
            )
            return evaluate(
                dataset=dataset,
                task=_task,
                scoring_metrics=[_NoKwargsExactMatch(), _NeedsContext()],
                experiment_name=experiment_name,
                task_threads=1,
                verbose=0,
                scoring_key_mapping={"reference": "expected_output"},
                **tolerance_kwargs,
            )

        aborting_error: BaseException | None = None
        try:
            _evaluate(body.aborting_experiment_name, None)
        except Exception as err:  # noqa: BLE001 — the abort IS the result here
            aborting_error = err

        # NOTE: the aborted run still leaves an experiment behind (evaluate()
        # registers it before scoring), but its id is not readable from here —
        # creation is only flushed by client.end() below. The caller resolves it
        # by name for teardown.
        tolerated_result = _evaluate(
            body.tolerated_experiment_name, ErrorTolerance.ALL_SCORING_ERRORS
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    aggregated = tolerated_result.aggregate_evaluation_scores().aggregated_scores

    return ExperimentScoringErrorSeedResponse(
        dataset_id=dataset_id,
        dataset_name=body.dataset_name,
        item_count=len(body.items),
        passing_metric_name=_PASSING_METRIC_NAME,
        failing_metric_name=_FAILING_METRIC_NAME,
        aborting=AbortingRunResult(
            aborted=aborting_error is not None,
            exception_type=type(aborting_error).__name__ if aborting_error else None,
            message=str(aborting_error) if aborting_error else None,
            experiment_name=body.aborting_experiment_name,
        ),
        tolerated=ToleratedRunResult(
            experiment_id=str(tolerated_result.experiment_id),
            experiment_name=tolerated_result.experiment_name
            or body.tolerated_experiment_name,
            aggregate_score_names=sorted(aggregated.keys()),
            aggregate_means={k: float(v.mean) for k, v in aggregated.items()},
            item_results=_scoring_error_item_results(tolerated_result),
        ),
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
