from typing import Any, Dict

import pytest

import opik
from opik.evaluation.metrics import Equals

from ..testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)


def _capital_task(item: Dict[str, Any]) -> Dict[str, Any]:
    return {"output": item["input"]["question"].split()[-1].rstrip("?")}


def test_get_dataset_item_experiment_results__one_item_across_experiments_and_versions(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(
        [
            {"input": {"question": "Capital of Poland?"}, "expected": "Warsaw"},
            {"input": {"question": "Capital of Spain?"}, "expected": "Madrid"},
        ]
    )
    first_version_hash = dataset.get_version_info().version_hash

    def run_experiment(name: str) -> str:
        return opik.evaluate(
            dataset=dataset,
            task=_capital_task,
            scoring_metrics=[Equals()],
            scoring_key_mapping={"reference": "expected"},
            experiment_name=name,
            verbose=0,
        ).experiment_id

    first_version_experiment_ids = {
        run_experiment(f"{experiment_name}-v1-a"),
        run_experiment(f"{experiment_name}-v1-b"),
    }
    # A new item creates a second version; the Poland item keeps its id.
    dataset.insert([{"input": {"question": "Capital of France?"}, "expected": "Paris"}])
    second_version_experiment_id = run_experiment(f"{experiment_name}-v2")
    opik.flush_tracker()

    poland = next(
        item for item in dataset.get_items() if "Poland" in item["input"]["question"]
    )

    every_version = opik_client.get_dataset_item_experiment_results(
        dataset_name=dataset_name,
        dataset_item_id=poland["id"],
        project_name=PROJECT_NAME,
    )

    assert {result.experiment_id for result in every_version} == (
        first_version_experiment_ids | {second_version_experiment_id}
    )
    assert len(every_version) == 3
    for result in every_version:
        assert result.dataset_item_id == poland["id"]
        assert result.dataset_item_data == {
            "input": {"question": "Capital of Poland?"},
            "expected": "Warsaw",
            "id": poland["id"],
        }
        assert result.evaluation_task_output == {"output": "Poland"}
        assert result.feedback_scores[0]["value"] == 0.0

    first_version_only = opik_client.get_dataset_item_experiment_results(
        dataset_name=dataset_name,
        dataset_item_id=poland["id"],
        dataset_versions=[first_version_hash],
        project_name=PROJECT_NAME,
    )
    assert {
        result.experiment_id for result in first_version_only
    } == first_version_experiment_ids

    with pytest.raises(opik.exceptions.DatasetVersionNotFound):
        opik_client.get_dataset_item_experiment_results(
            dataset_name=dataset_name,
            dataset_item_id=poland["id"],
            dataset_versions=["no-such-version"],
            project_name=PROJECT_NAME,
        )


def test_get_test_suite_item_experiment_results__one_result_per_run(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    suite = opik_client.create_test_suite(
        name=dataset_name,
        project_name=PROJECT_NAME,
        global_execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )
    suite.insert(
        [
            {"data": {"input": {"question": "Capital of Poland?"}}},
            {"data": {"input": {"question": "Capital of Spain?"}}},
        ]
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": _capital_task(item)["output"]}

    experiment_ids = {
        opik.run_tests(
            test_suite=suite,
            task=task,
            experiment_name=f"{experiment_name}-{run}",
            verbose=0,
            generate_report=False,
        ).experiment_id
        for run in ("a", "b")
    }
    opik.flush_tracker()

    poland = next(item for item in suite.get_items() if "Poland" in str(item))

    results = opik_client.get_test_suite_item_experiment_results(
        name=dataset_name, item_id=poland["id"], project_name=PROJECT_NAME
    )

    assert {result.experiment_id for result in results} == experiment_ids
    assert len(results) == 4
    assert len({result.trace_id for result in results}) == 4
    for result in results:
        assert result.dataset_item_id == poland["id"]
        assert result.dataset_item_data["input"] == {"question": "Capital of Poland?"}
