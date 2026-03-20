"""E2E test for LocalRunnerTask with evaluate().

Verifies that LocalRunnerTask can submit jobs to a running local runner
and return results that the evaluation engine records as experiment items.
"""

import time

from opik.evaluation import evaluate
from opik.evaluation.local_runner_task import LocalRunnerTask

from ..conftest import OPIK_E2E_TESTS_PROJECT_NAME
from .conftest import RunnerInfo
from .test_runner_e2e import wait_for_agent_registration


def test_local_runner_task__evaluate__happyflow(
    opik_client, api_client, runner_process: RunnerInfo, project_id
):
    """LocalRunnerTask used as the task in evaluate() produces experiment items."""
    wait_for_agent_registration(api_client, "echo", project_id)

    dataset_name = f"lrt-e2e-dataset-{int(time.time())}"
    dataset = opik_client.create_dataset(name=dataset_name)
    dataset.insert(
        [
            {"message": f"hello-lrt-{int(time.time())}"},
        ]
    )

    task = LocalRunnerTask(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        agent_name="echo",
        timeout_seconds=30,
        poll_interval_seconds=0.5,
    )

    result = evaluate(
        dataset=dataset,
        task=task,
        scoring_metrics=[],
        experiment_name=f"lrt-e2e-{int(time.time())}",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        verbose=0,
    )

    assert len(result.test_results) == 1
    test_result = result.test_results[0]
    assert "echo:" in str(test_result.task_output.get("output", ""))
