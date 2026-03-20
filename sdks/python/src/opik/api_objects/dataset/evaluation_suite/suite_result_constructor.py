"""
Suite result construction logic for evaluation suites.

This module handles building EvaluationSuiteResult from raw evaluation results,
including pass/fail determination based on execution policies.
"""

from collections import defaultdict
from typing import Dict, List, Optional

from opik.api_objects.dataset import dataset_item
from opik.evaluation import evaluation_result, test_result

from . import types as suite_types
from .evaluation_suite_result import is_score_passed


def build_suite_result(
    eval_result: evaluation_result.EvaluationResult,
    suite_name: Optional[str] = None,
    total_time: Optional[float] = None,
) -> suite_types.EvaluationSuiteResult:
    """
    Build an EvaluationSuiteResult from an EvaluationResult.

    Groups test results by dataset item and computes pass/fail status
    based on execution policies stored in each item.

    Pass/fail logic:
    - A RUN passes if all its assertion scores pass (value=True or value=1)
    - An ITEM passes if runs_passed >= pass_threshold
    - The SUITE passes if all items pass

    Args:
        eval_result: The raw evaluation result from the evaluation engine.

    Returns:
        EvaluationSuiteResult with pass/fail status for each item and the suite.
    """
    results_by_item: Dict[str, List[test_result.TestResult]] = defaultdict(list)
    items_cache: Dict[str, Optional[dataset_item.DatasetItem]] = {}

    for result in eval_result.test_results:
        item_id = result.test_case.dataset_item_id
        results_by_item[item_id].append(result)
        if item_id not in items_cache:
            items_cache[item_id] = result.test_case.dataset_item

    item_results: Dict[str, suite_types.ItemResult] = {}
    items_passed = 0

    for item_id, item_test_results in results_by_item.items():
        item = items_cache.get(item_id)
        pass_threshold = 1
        configured_runs_per_item = 1
        if item is not None and item.execution_policy is not None:
            if item.execution_policy.pass_threshold is not None:
                pass_threshold = item.execution_policy.pass_threshold
            if item.execution_policy.runs_per_item is not None:
                configured_runs_per_item = item.execution_policy.runs_per_item

        runs_passed = sum(
            1
            for r in item_test_results
            if not r.score_results or all(is_score_passed(s) for s in r.score_results)
        )

        passed = runs_passed >= pass_threshold

        if passed:
            items_passed += 1

        item_results[item_id] = suite_types.ItemResult(
            dataset_item_id=item_id,
            passed=passed,
            runs_passed=runs_passed,
            runs_total=len(item_test_results),
            configured_runs_per_item=configured_runs_per_item,
            pass_threshold=pass_threshold,
            test_results=sorted(item_test_results, key=lambda r: r.trial_id),
        )

    return suite_types.EvaluationSuiteResult(
        items_passed=items_passed,
        items_total=len(results_by_item),
        item_results=item_results,
        evaluation_result_=eval_result,
        suite_name=suite_name,
        total_time=total_time,
    )
