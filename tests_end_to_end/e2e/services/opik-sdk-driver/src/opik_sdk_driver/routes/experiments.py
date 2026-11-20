import atexit

import opik
from fastapi import APIRouter, Header
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals, base_metric, score_result
from opik.evaluation.metrics.heuristics.readability import Readability

from ..opik_factory import make_opik_client
from ..schemas import (
    CompareExperimentResult,
    ExperimentCancelRequest,
    ExperimentCancelResponse,
    ExperimentCompareSeedRequest,
    ExperimentCompareSeedResponse,
    ExperimentEvaluateRequest,
    ExperimentEvaluateResponse,
    ExperimentItemFingerprint,
    ExperimentItemScore,
    ExperimentLifecycleRequest,
    ExperimentLifecycleResponse,
    ExperimentReadItemsRequest,
    ExperimentReadItemsResponse,
    MixedScoreEvaluateRequest,
    MixedScoreEvaluateResponse,
    MixedScoreResult,
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


@router.post("/cancel", response_model=ExperimentCancelResponse, status_code=200)
def cancel_experiments(
    body: ExperimentCancelRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentCancelResponse:
    """Stop running experiments through `cancel_experiments`, new in 2.2.122's regen.

    Reached through the public `Opik.rest_client` property rather than importing
    anything out of `opik.rest_api`: the method is one of the bindings opik#9211
    generated, and the question this route exists to answer is whether it is
    callable at all on a live backend — a generated client that lost the method,
    or whose request model no longer matches the endpoint, fails here.

    Returns what the call returned (nothing) rather than reading the experiments
    back. The stop having APPLIED is a separate claim with its own failure mode —
    the endpoint answers 204 for ids it is not tracking — so the caller asserts
    it off `/experiments/lifecycle` instead of inferring it from this reply.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        returned = client.rest_client.experiments.cancel_experiments(ids=body.ids)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return ExperimentCancelResponse(
        requested=len(body.ids),
        returned_none=returned is None,
    )


@router.post("/lifecycle", response_model=ExperimentLifecycleResponse, status_code=200)
def read_experiment_lifecycle(
    body: ExperimentLifecycleRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> ExperimentLifecycleResponse:
    """Read `status` and `finished_at` back through the regenerated client.

    `Experiment.get_experiment_data()` is the public accessor for the whole
    `ExperimentPublic` model, so this drives the generated DESERIALISATION of
    the two fields opik#9211 added — which is where a regen breaks silently: a
    field the model no longer declares is dropped by pydantic and reads here as
    `None`, not as an error.

    Timestamps are rendered with `isoformat()` rather than passed through as the
    raw wire string, because the SDK has already parsed them into `datetime` by
    the time this route sees them. That parse is itself part of what is under
    test: a `finished_at` the client could not read would have raised inside
    `get_experiment_data`, before this line.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        experiment = client.get_experiment_by_id(body.experiment_id)
        data = experiment.get_experiment_data()
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return ExperimentLifecycleResponse(
        experiment_id=body.experiment_id,
        # `str(...)` only once it is known to be there: an absent status must
        # reach the caller as None so its assertion fails, not as "None".
        status=str(data.status) if data.status is not None else None,
        finished_at=data.finished_at.isoformat() if data.finished_at is not None else None,
        created_at=data.created_at.isoformat() if data.created_at is not None else None,
        trace_count=data.trace_count,
        dataset_item_count=data.dataset_item_count,
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


#: What each `value_kind` makes the metric return.
#:
#: Five of the six are not finite real numbers and so must be turned into failed
#: scores by `mark_invalid_score_values_as_failed`; `finite` is the control that
#: proves the engine is not simply failing everything. A string is in the list
#: because `ScoreResult.value` is typed as a float but nothing enforces it, and
#: it is the only kind whose `repr` is long enough to reach the 200-character
#: cap.
MIXED_SCORE_KINDS = ("finite", "nan", "pos_inf", "neg_inf", "none", "long_string")


def _mixed_score_value(value_kind: str, long_string_length: int):
    if value_kind == "finite":
        return 1.0
    if value_kind == "nan":
        return float("nan")
    if value_kind == "pos_inf":
        return float("inf")
    if value_kind == "neg_inf":
        return float("-inf")
    if value_kind == "none":
        return None
    if value_kind == "long_string":
        # A single repeated character, so the `reprlib` truncation is readable:
        # the result is quotes, 98 characters, "...", 99 characters.
        return "s" * long_string_length
    raise ValueError(f"unknown value_kind {value_kind!r}; expected one of {MIXED_SCORE_KINDS}")


@router.post(
    "/mixed-score-evaluate",
    response_model=MixedScoreEvaluateResponse,
    status_code=201,
)
def mixed_score_evaluate(
    body: MixedScoreEvaluateRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> MixedScoreEvaluateResponse:
    """Run `evaluate()` with one metric that returns a different value per item.

    The shape opik#9396 changed. A metric whose value is not a finite real
    number — NaN, +-inf, None, a string — used to reach the feedback-score batch
    and either poison the console average or get the whole upload rejected by
    the backend. The engine now treats such a score like a metric that RAISED:
    `scoring_failed` set, the value replaced with `0.0`, the metric's own reason
    moved to `metadata.original_reason`, and a capped `repr` of the original
    under `metadata.original_value`.

    One metric over one dataset in ONE run, not six runs of one value each:
    what has to be observable is that the failed scores are excluded from the
    average while the finite ones still form it, and a run containing only
    invalid scores has no denominator at all (`calculate_aggregated_statistics`
    drops the metric entirely). A per-value run could not show the mixture.

    Deliberately provider-free and deterministic: the metric is a lookup on the
    item's own `value_kind`, so there is no LLM, no network and no wall-clock
    dependence. The task echoes the item.

    `task_threads` defaults to 1 so the per-item order in the response is the
    dataset order, which keeps a failure message pointing at the item a reader
    can find.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    kind_by_key = {item.key: item.value_kind for item in body.items}

    class MixedMetric(base_metric.BaseMetric):
        """Returns whatever its item's `value_kind` names, with a fixed reason.

        The reason is always supplied so the engine's relocation of it is
        assertable: a failed score must carry the ENGINE's reason and keep the
        metric's under `metadata.original_reason`, and with no reason at all
        that key would legitimately be absent and the assertion vacuous.
        """

        def __init__(self, name: str) -> None:
            super().__init__(name=name, track=False)

        def score(self, key: str, **ignored):
            return score_result.ScoreResult(
                name=self.name,
                value=_mixed_score_value(kind_by_key[key], body.long_string_length),
                reason=body.reason,
            )

    try:
        dataset = client.create_dataset(
            name=body.dataset_name,
            description=body.dataset_description,
            project_name=body.project_name,
        )
        dataset.insert(
            [
                {"key": item.key, "input": item.key, "task_output": item.key}
                for item in body.items
            ]
        )

        def _task(item: dict) -> dict:
            return {"output": item["task_output"], "key": item["key"]}

        # Not wrapped in a try/except: "the run did not raise" is one of the
        # claims, and an exception here has to reach the caller as a 500 rather
        # than be reported as a result.
        result = evaluate(
            dataset=dataset,
            task=_task,
            scoring_metrics=[MixedMetric(name=body.metric_name)],
            experiment_name=body.experiment_name,
            project_name=body.project_name,
            task_threads=body.task_threads,
            verbose=0,
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    results: list[MixedScoreResult] = []
    for test_result in result.test_results:
        item_content = test_result.test_case.dataset_item_content or {}
        key = str(item_content.get("key", ""))
        for sr in test_result.score_results:
            if sr.name != body.metric_name:
                continue
            metadata = sr.metadata or {}
            results.append(
                MixedScoreResult(
                    dataset_item_id=str(test_result.test_case.dataset_item_id),
                    key=key,
                    value_kind=kind_by_key.get(key, ""),
                    score_value=float(sr.value),
                    scoring_failed=bool(sr.scoring_failed),
                    reason=sr.reason,
                    original_value=metadata.get("original_value"),
                    original_reason=metadata.get("original_reason"),
                )
            )

    # The console's own numbers, read off the same statistics the report prints
    # rather than recomputed here — a recomputation would agree with itself
    # however the engine had changed.
    stats = result.aggregate_evaluation_scores().aggregated_scores.get(body.metric_name)

    return MixedScoreEvaluateResponse(
        experiment_id=str(result.experiment_id),
        experiment_name=result.experiment_name or body.experiment_name,
        dataset_id=str(result.dataset_id),
        item_count=len(body.items),
        results=results,
        aggregate_mean=None if stats is None else float(stats.mean),
        aggregate_failed_count=None if stats is None else int(stats.failed_count),
        aggregate_values=[] if stats is None else [float(v) for v in stats.values],
    )
