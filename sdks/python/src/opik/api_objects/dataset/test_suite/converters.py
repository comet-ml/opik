"""Converters between raw dataset/REST formats and test suite formats."""

from __future__ import annotations

from typing import List, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from opik.evaluation.suite_evaluators.llm_judge import LLMJudge

from opik.api_objects.dataset import dataset_item
from opik.api_objects.dataset.test_suite import types as suite_types
from opik.rest_api.types import (
    evaluator_item_public as rest_evaluator_item_public,
    execution_policy_public as rest_execution_policy_public,
)
from .. import execution_policy


def evaluators_to_assertions(evaluators: List[LLMJudge]) -> List[str]:
    """Extract assertion strings from a list of LLMJudge instances."""
    assertions: List[str] = []
    for evaluator in evaluators:
        assertions.extend(evaluator.assertions)
    return assertions


def version_evaluators_to_assertions(
    evaluators: Optional[List[rest_evaluator_item_public.EvaluatorItemPublic]],
) -> List[str]:
    """Extract assertion strings from REST evaluator items on a dataset version."""
    from opik.evaluation.suite_evaluators import llm_judge
    from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config

    assertions: List[str] = []
    if evaluators:
        for evaluator in evaluators:
            if evaluator.type == "llm_judge":
                cfg = llm_judge_config.LLMJudgeConfig(**evaluator.config)
                judge = llm_judge.LLMJudge.from_config(cfg)
                assertions.extend(judge.assertions)
    return assertions


def version_policy_to_execution_policy(
    policy: Optional[rest_execution_policy_public.ExecutionPolicyPublic],
) -> execution_policy.ExecutionPolicy:
    """Convert a REST execution policy object to an ExecutionPolicy dict."""
    if policy:
        return execution_policy.ExecutionPolicy(
            runs_per_item=policy.runs_per_item or 1,
            pass_threshold=policy.pass_threshold or 1,
        )
    return execution_policy.DEFAULT_EXECUTION_POLICY.copy()


def dataset_item_to_suite_item_dict(
    item: dataset_item.DatasetItem,
) -> suite_types.TestSuiteItem:
    """Convert a DatasetItem into a TestSuiteItem dict with decoded assertions."""
    result = suite_types.TestSuiteItem(
        id=item.id,
        data=item.get_content(),
        assertions=version_evaluators_to_assertions(item.evaluators),
    )
    if item.description is not None:
        result["description"] = item.description
    if item.execution_policy is not None:
        result["execution_policy"] = {
            "runs_per_item": item.execution_policy.runs_per_item or 1,
            "pass_threshold": item.execution_policy.pass_threshold or 1,
        }
    return result
