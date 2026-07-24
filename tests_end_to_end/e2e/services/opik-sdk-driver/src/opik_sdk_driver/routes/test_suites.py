import atexit

import opik
from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..schemas import (
    TestSuiteCreate,
    TestSuiteInsertItemsRequest,
    TestSuiteInsertItemsResponse,
    TestSuiteResponse,
    TestSuiteRunRequest,
    TestSuiteRunResponse,
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
    """Insert items into an existing test suite by name (idempotent get-or-create).

    Resolves the suite within the caller's `project_name` scope; without that,
    same-named suites across projects could collide.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    try:
        suite = client.get_or_create_test_suite(
            name=body.suite_name, project_name=body.project_name
        )
        items = [item.model_dump(exclude_none=True) for item in body.items]
        suite.insert(items)
        suite_id = str(suite.id)
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return TestSuiteInsertItemsResponse(suite_id=suite_id, inserted=len(body.items))


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
    return TestSuiteRunResponse(
        experiment_id=str(result.experiment_id) if result.experiment_id else None,
        experiment_name=result.experiment_name,
        pass_rate=result.pass_rate,
        items_passed=items_passed,
        items_failed=items_total - items_passed,
        items_total=items_total,
    )
