"""
Evaluation Suite API for regression testing LLM applications.

Evaluation Suites are pre-configured regression test suites that let you
validate that prompt changes, model updates, or code modifications don't
break existing functionality.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Dict, List, Optional, TYPE_CHECKING

from opik import id_helpers
from opik.api_objects.prompt import base_prompt
from opik.api_objects.dataset import dataset, dataset_item

from . import types as suite_types
from .. import validators, execution_policy, rest_operations

if TYPE_CHECKING:
    from opik.evaluation.suite_evaluators import llm_judge


LOGGER = logging.getLogger(__name__)

LLMTask = Callable[[Dict[str, Any]], Any]


class EvaluationSuite:
    """
    A pre-configured regression test suite for LLM applications.

    Evaluation Suites let you:
    - Define test cases with inputs and context
    - Configure evaluation criteria (LLMJudge evaluators only)
    - Run tests against any task function

    Suite-level evaluators and execution policy are stored in the dataset's
    metadata and read by the evaluation engine when running the suite.

    Note:
        Evaluation suites only support LLMJudge evaluators. Other metric types
        (like Equals, Contains, etc.) are not supported.

    Example:
        >>> from opik import Opik
        >>> from opik.evaluation.suite_evaluators import LLMJudge
        >>>
        >>> client = Opik()
        >>>
        >>> suite_evaluator = LLMJudge(
        ...     assertions=[
        ...         "Response does not contain hallucinated information",
        ...         "Response is helpful to the user",
        ...     ]
        ... )
        >>>
        >>> suite = client.create_evaluation_suite(
        ...     name="Refund Policy Tests",
        ...     description="Regression tests for refund scenarios",
        ...     evaluators=[suite_evaluator]
        ... )
        >>>
        >>> suite.add_item(
        ...     data={"user_input": "How do I get a refund?", "user_tier": "premium"},
        ...     evaluators=[
        ...         LLMJudge(assertions=["Response is polite"]),
        ...     ]
        ... )
        >>>
        >>> results = suite.run(task=my_llm_function)
    """

    def __init__(
        self,
        name: str,
        dataset_: dataset.Dataset,
    ):
        """
        Initialize an EvaluationSuite.

        Suite-level evaluators and execution policy are stored in the dataset's
        metadata. Use `create_evaluation_suite()` to create a suite with config.

        Args:
            name: The name of the evaluation suite.
            dataset_: The underlying dataset storing suite items and config.
        """
        self._name = name
        self._dataset = dataset_

    @property
    def name(self) -> str:
        """The name of the evaluation suite."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the evaluation suite."""
        return self._dataset.description

    @property
    def dataset(self) -> dataset.Dataset:
        """The underlying dataset storing suite items."""
        return self._dataset

    def get_items(
        self,
        evaluator_model: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """
        Retrieve suite items as a list of dictionaries.

        Each item dict has keys:
        - "data": the test case data (dict)
        - "evaluators": list of LLMJudge instances (or empty list)
        - "execution_policy": ExecutionPolicyItem or None

        Args:
            evaluator_model: Optional model name to use for LLMJudge evaluators.

        Returns:
            A list of item dictionaries.
        """
        from opik.evaluation.suite_evaluators import llm_judge
        from opik.evaluation.suite_evaluators.llm_judge import (
            config as llm_judge_config,
        )

        result = []
        for item in self._dataset.__internal_api__stream_items_as_dataclasses__():
            evaluator_objects: list[llm_judge.LLMJudge] = []
            if item.evaluators:
                for e in item.evaluators:
                    if e.type == "llm_judge":
                        cfg = llm_judge_config.LLMJudgeConfig(**e.config)
                        evaluator_objects.append(
                            llm_judge.LLMJudge.from_config(
                                cfg, init_kwargs={"model": evaluator_model}
                            )
                        )

            result.append(
                {
                    "data": item.get_content(),
                    "evaluators": evaluator_objects,
                    "execution_policy": item.execution_policy,
                }
            )
        return result

    def update(
        self,
        *,
        execution_policy: execution_policy.ExecutionPolicy,
        evaluators: List[llm_judge.LLMJudge],
    ) -> None:
        """
        Update the suite-level execution policy and evaluators.

        Creates a new dataset version based on the current latest version
        with the updated configuration.

        Both arguments are mandatory.

        Args:
            execution_policy: New execution policy for the suite.
            evaluators: New suite-level LLMJudge evaluators.

        Raises:
            TypeError: If any evaluator is not an LLMJudge instance.
            ValueError: If no current version exists to base the update on.
        """
        validators.validate_evaluators(evaluators, "suite-level evaluators")

        version_info = self._dataset.get_version_info()
        if version_info is None or version_info.id is None:
            raise ValueError(
                "Cannot update suite: no existing version found. "
                "Use create_evaluation_suite() to create the suite first."
            )

        rest_operations.update_evaluation_suite_dataset(
            rest_client=self._dataset._rest_client,
            dataset_id=self._dataset.id,
            base_version_id=version_info.id,
            evaluators=evaluators,
            exec_policy=execution_policy,
        )

    def delete_items(self, item_ids: List[str]) -> None:
        """
        Delete items from the evaluation suite by their IDs.

        Args:
            item_ids: List of item IDs to delete.
        """
        self._dataset.delete(item_ids)

    def get_execution_policy(self) -> execution_policy.ExecutionPolicy:
        """
        Get the suite-level execution policy.

        Returns:
            ExecutionPolicy dict with runs_per_item and pass_threshold.
        """
        return self._dataset.get_execution_policy()

    def get_evaluators(
        self,
        evaluator_model: Optional[str] = None,
    ) -> List[llm_judge.LLMJudge]:
        """
        Get the suite-level evaluators as LLMJudge instances.

        Args:
            evaluator_model: Optional model name to use for LLMJudge evaluators.

        Returns:
            List of LLMJudge instances.
        """
        return self._dataset.get_evaluators(evaluator_model=evaluator_model)

    def add_item(
        self,
        data: Dict[str, Any],
        evaluators: Optional[List[llm_judge.LLMJudge]] = None,
        execution_policy: Optional[execution_policy.ExecutionPolicy] = None,
    ) -> None:
        """
        Add a test case to the evaluation suite.

        Args:
            data: Dictionary containing the test case data. This is passed to
                the task function and can contain any fields needed.
                Example: {"user_input": "How do I get a refund?", "user_tier": "premium"}
            evaluators: Item-specific LLMJudge evaluators. If provided, these are
                used in addition to suite-level evaluators.
            execution_policy: Item-specific execution policy override.
                Example: {"runs_per_item": 3, "pass_threshold": 2}

        Raises:
            TypeError: If any evaluator is not an LLMJudge instance.

        Example:
            >>> suite.add_item(
            ...     data={"user_input": "How do I get a refund?", "user_tier": "premium"},
            ...     evaluators=[
            ...         LLMJudge(assertions=["Response is polite"]),
            ...     ]
            ... )
        """
        if evaluators:
            validators.validate_evaluators(evaluators, "item-level evaluators")

        item_id = id_helpers.generate_id()

        # Convert LLMJudge evaluators to EvaluatorItem format for the API
        evaluator_items = None
        if evaluators:
            evaluator_items = [
                dataset_item.EvaluatorItem(
                    name=e.name,
                    type="llm_judge",
                    config=e.to_config().model_dump(by_alias=True),
                )
                for e in evaluators
            ]

        # Convert execution_policy to ExecutionPolicyItem format for the API
        execution_policy_item = None
        if execution_policy:
            execution_policy_item = dataset_item.ExecutionPolicyItem(
                runs_per_item=execution_policy.get("runs_per_item"),
                pass_threshold=execution_policy.get("pass_threshold"),
            )

        ds_item = dataset_item.DatasetItem(
            id=item_id,
            evaluators=evaluator_items,
            execution_policy=execution_policy_item,
            **data,
        )
        self._dataset.__internal_api__insert_items_as_dataclasses__([ds_item])

    def run(
        self,
        task: LLMTask,
        *,
        experiment_name_prefix: Optional[str] = None,
        experiment_name: Optional[str] = None,
        project_name: Optional[str] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        prompts: Optional[List[base_prompt.BasePrompt]] = None,
        experiment_tags: Optional[List[str]] = None,
        verbose: int = 1,
        worker_threads: int = 16,
        evaluator_model: Optional[str] = None,
    ) -> suite_types.EvaluationSuiteResult:
        """
        Run the evaluation suite against a task function.

        The task function receives each test item's data dict and must return
        a dict with "input" and "output" keys for the evaluators.

        Args:
            task: A callable that takes a dict (the item's data) and returns
                a dict with "input" and "output" keys.
            experiment_name_prefix: Optional prefix for auto-generated experiment name.
            experiment_name: Optional explicit name for the experiment.
            project_name: Optional project name for tracking.
            experiment_config: Optional configuration dict for the experiment.
            prompts: Optional list of Prompt objects to associate with the experiment.
            experiment_tags: Optional list of tags to associate with the experiment.
            verbose: Verbosity level (0=silent, 1=normal, 2=detailed).
            worker_threads: Number of threads for parallel task execution.
            evaluator_model: Optional model name to use for LLMJudge evaluators.
                If not provided, uses the default model.

        Returns:
            EvaluationSuiteResult with pass/fail status based on execution policy.

        Example:
            >>> def my_llm_task(data: dict) -> dict:
            ...     response = call_my_llm(data["user_input"], user_tier=data.get("user_tier"))
            ...     return {"input": data, "output": response}
            >>>
            >>> result = suite.run(task=my_llm_task, evaluator_model="gpt-4o")
            >>> print(f"Suite passed: {result.passed}")
            >>> print(f"Items passed: {result.items_passed}/{result.items_total}")
        """
        from opik.evaluation import evaluator as opik_evaluator
        from . import suite_result_constructor

        eval_result = opik_evaluator.evaluate_suite(
            dataset=self._dataset,
            task=task,
            experiment_name_prefix=experiment_name_prefix,
            experiment_name=experiment_name,
            project_name=project_name,
            experiment_config=experiment_config,
            prompts=prompts,
            experiment_tags=experiment_tags,
            verbose=verbose,
            task_threads=worker_threads,
            evaluator_model=evaluator_model,
        )

        return suite_result_constructor.build_suite_result(eval_result)
