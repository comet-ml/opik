import atexit

import opik
from fastapi import APIRouter, Header, HTTPException

from ..opik_factory import make_opik_client
from ..schemas import (
    TestSuiteAssertionResult,
    TestSuiteCreate,
    TestSuiteEvaluatorSchemaRequest,
    TestSuiteEvaluatorSchemaResponse,
    TestSuiteInsertItemsRequest,
    TestSuiteInsertItemsResponse,
    TestSuiteResponse,
    TestSuiteRunRequest,
    TestSuiteRunResponse,
    TestSuiteSchemaItem,
)

router = APIRouter(prefix="/test-suites", tags=["test-suites"])


@router.post("", response_model=TestSuiteResponse, status_code=201)
def create_test_suite(
    body: TestSuiteCreate,
    x_opik_api_key: str | None = Header(default=None),
) -> TestSuiteResponse:
    """Wraps client.create_test_suite(...) + optional suite.insert(items).

    flush=True on client.end() drains the streamer that insert() enqueues to.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        global_execution_policy = None
        if body.runs_per_item is not None or body.pass_threshold is not None:
            global_execution_policy = {
                "runs_per_item": body.runs_per_item or 1,
                "pass_threshold": body.pass_threshold or 1,
            }

        suite = client.create_test_suite(
            name=body.name,
            description=body.description,
            project_name=body.project_name,
            global_assertions=body.global_assertions,
            global_execution_policy=global_execution_policy,
        )
        if body.items:
            suite.insert([item.model_dump(exclude_none=True) for item in body.items])
        # suite.id is a lazy cached_property that fetches from the backend on
        # first read; resolve it while the client is still open, before end().
        suite_id, suite_name = str(suite.id), suite.name
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return TestSuiteResponse(id=suite_id, name=suite_name)


@router.post(
    "/insert-items",
    response_model=TestSuiteInsertItemsResponse,
    status_code=200,
)
def insert_test_suite_items(
    body: TestSuiteInsertItemsRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> TestSuiteInsertItemsResponse:
    """Insert items into an existing test suite by name.

    Resolves the suite within the caller's `project_name` scope; without that,
    same-named suites across projects could collide.

    `body.resolve_via` picks the factory. It matters because the suite object's
    local content-hash state — and therefore whether `insert` can recognise an
    item the suite already holds — depends on how the object was built. Each
    request constructs its own client, so no factory ever inherits hashes
    another call happened to compute.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        if body.resolve_via == "list":
            suites = client.get_test_suites(project_name=body.project_name)
            matching = [s for s in suites if s.name == body.suite_name]
            if len(matching) != 1:
                # Loud rather than a silent get-or-create fallback: a caller
                # asking for the listing path is testing that path, and quietly
                # substituting another one would turn a real regression into a
                # pass. 404 rather than 500 so the reason reaches the spec
                # instead of FastAPI's opaque "Internal Server Error".
                raise HTTPException(
                    status_code=404,
                    detail=(
                        f"get_test_suites() returned {len(matching)} suites named "
                        f"{body.suite_name!r} in project {body.project_name!r}; expected exactly 1"
                    ),
                )
            suite = matching[0]
        else:
            suite = client.get_or_create_test_suite(
                name=body.suite_name, project_name=body.project_name
            )
        items = [item.model_dump(exclude_none=True) for item in body.items]
        suite.insert(items, deduplication=body.deduplication)
        suite_id = str(suite.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return TestSuiteInsertItemsResponse(suite_id=suite_id, inserted=len(body.items))


@router.post(
    "/evaluator-schema",
    response_model=TestSuiteEvaluatorSchemaResponse,
    status_code=200,
)
def set_test_suite_evaluator_schema(
    body: TestSuiteEvaluatorSchemaRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> TestSuiteEvaluatorSchemaResponse:
    """Write a suite version whose llm_judge evaluator schema carries ITEM TYPES.

    Why a route of its own, rather than a parameter on `create_test_suite`:
    every public entry point for suite-level assertions takes a list of plain
    strings and funnels it through
    `dataset.validators.resolve_evaluators(assertions, None, ...)`, which builds
    one `LLMJudge(assertions=[...])` — and every schema item that produces is
    `BOOLEAN`. The branch opik#8557 added only runs for an `INTEGER` or `DOUBLE`
    item, so no public parameter can reach it.

    The config is NOT hand-assembled. An `LLMJudge` is built from the same
    assertion strings the public shorthand would use, its own `to_config()` is
    taken, and only each schema item's `type` is replaced before
    `LLMJudge.from_config` reads it back. So the model block, the prompt
    messages and the variable mapping are byte-for-byte what the SDK itself
    writes, and the item type is the single variable — which is what makes a
    difference in the run attributable to it.

    `rest_operations.update_test_suite_dataset` /
    `create_initial_test_suite_version` are the same functions
    `TestSuite.update_test_settings` calls one layer up; they are reached
    directly only because that layer's signature cannot carry an evaluator. The
    REST client itself is never touched.

    Returns the suite re-read from the backend — `get_global_assertions()` and
    the version's own evaluator schema — so a caller asserts the round trip
    instead of trusting the write.
    """
    from opik.api_objects.dataset import execution_policy, rest_operations
    from opik.evaluation.suite_evaluators.llm_judge import LLMJudge
    from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config

    if not body.schema_items:
        raise HTTPException(
            status_code=422,
            detail="schema_items must hold at least one item",
        )

    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        suite = client.get_or_create_test_suite(
            name=body.suite_name, project_name=body.project_name
        )
        dataset = suite.__internal_api__dataset__

        base_judge = LLMJudge(assertions=[item.description for item in body.schema_items])
        config = base_judge.to_config()
        config.schema_ = [
            llm_judge_config.LLMJudgeSchemaItem(
                name=item.name, type=item.type, description=item.description
            )
            for item in body.schema_items
        ]
        typed_judge = LLMJudge.from_config(config)

        version_info = dataset.get_version_info()
        policy = (
            suite.get_global_execution_policy()
            if version_info is not None
            else execution_policy.DEFAULT_EXECUTION_POLICY.copy()
        )
        if version_info is None:
            rest_operations.create_initial_test_suite_version(
                rest_client=dataset._rest_client,
                dataset_id=dataset.id,
                evaluators=[typed_judge],
                exec_policy=policy,
            )
        else:
            rest_operations.update_test_suite_dataset(
                rest_client=dataset._rest_client,
                dataset_id=dataset.id,
                base_version_id=version_info.id,
                evaluators=[typed_judge],
                exec_policy=policy,
                change_description="Typed evaluator schema written by the e2e bridge",
            )

        # Re-read through a FRESH suite object. The one above has the version it
        # was built with cached, so asking it would report what was sent rather
        # than what landed.
        reread = client.get_or_create_test_suite(
            name=body.suite_name, project_name=body.project_name
        )
        reread_dataset = reread.__internal_api__dataset__
        landed = reread_dataset.get_version_info()
        if landed is None or not landed.evaluators:
            raise HTTPException(
                status_code=500,
                detail=(
                    f"suite {body.suite_name!r} carries no evaluators after the write; "
                    "the typed schema did not land"
                ),
            )

        schema_items: list[TestSuiteSchemaItem] = []
        for evaluator in landed.evaluators:
            if evaluator.type != "llm_judge":
                continue
            cfg = llm_judge_config.LLMJudgeConfig(**evaluator.config)
            schema_items.extend(
                TestSuiteSchemaItem(
                    name=item.name, type=item.type, description=item.description
                )
                for item in cfg.schema_
            )

        return TestSuiteEvaluatorSchemaResponse(
            suite_id=str(reread.id),
            version_name=reread.get_current_version_name(),
            global_assertions=reread.get_global_assertions(),
            schema_items=schema_items,
        )
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)


@router.post("/run", response_model=TestSuiteRunResponse, status_code=200)
def run_test_suite(
    body: TestSuiteRunRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> TestSuiteRunResponse:
    """Run a test suite with a deterministic task that always returns body.task_output.

    Combined with tautological assertions like "The response contains PASS"
    and task_output="PASS", the LLM judge's verdict is mechanically predictable.
    The entire suite-run code path (including the LLM judging call) is exercised.
    """
    # Mirror the experiments route: opik.run_tests calls get_global_client() and
    # ignores any locally-constructed client. Bind the request-scoped client so
    # auth/workspace context propagates into the run path.
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)
    try:
        suite = client.get_or_create_test_suite(
            name=body.suite_name, project_name=body.project_name
        )

        def task(item: dict) -> dict:
            return {"input": item, "output": body.task_output}

        run_kwargs: dict = {
            "test_suite": suite,
            "task": task,
            "experiment_name": body.experiment_name,
            "verbose": 0,
            "generate_report": False,
        }
        if body.judge_model:
            run_kwargs["model"] = body.judge_model
        result = opik.run_tests(**run_kwargs)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    items_passed = int(result.items_passed)
    items_total = int(result.items_total)

    assertions: list[TestSuiteAssertionResult] | None = None
    if body.include_assertions:
        assertions = [
            TestSuiteAssertionResult(
                dataset_item_id=item_id,
                trial_id=int(test_result.trial_id),
                name=score.name,
                value=float(score.value),
                reason=score.reason,
                category_name=score.category_name,
                scoring_failed=bool(score.scoring_failed),
                metadata=score.metadata,
            )
            for item_id, item_result in result.item_results.items()
            for test_result in item_result.test_results
            for score in test_result.score_results
        ]

    return TestSuiteRunResponse(
        experiment_id=str(result.experiment_id) if result.experiment_id else None,
        experiment_name=result.experiment_name,
        pass_rate=result.pass_rate,
        items_passed=items_passed,
        items_failed=items_total - items_passed,
        items_total=items_total,
        item_ids=sorted(result.item_results.keys()),
        item_assertions=assertions,
        total_time=result.total_time,
    )
