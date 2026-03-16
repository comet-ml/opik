"""
Evaluation Suite API for regression testing LLM applications.

Evaluation Suites are pre-configured regression test suites that let you
validate that prompt changes, model updates, or code modifications don't
break existing functionality.
"""

from __future__ import annotations

import functools
import logging
from typing import Any, Callable, Dict, List, Optional

from opik import id_helpers
from opik.api_objects.prompt import base_prompt
from opik.api_objects.dataset import dataset, dataset_item

from . import types as suite_types
from .. import validators, execution_policy, rest_operations


LOGGER = logging.getLogger(__name__)

LLMTask = Callable[[Dict[str, Any]], Any]


def validate_task_result(result: Any) -> Dict[str, Any]:
    if not isinstance(result, dict):
        raise TypeError(
            f"The task function must return a dict with 'input' and "
            f"'output' keys, but it returned {type(result).__name__}. "
            f"Example: return {{'input': data, 'output': response}}"
        )
    missing = {"input", "output"} - result.keys()
    if missing:
        raise ValueError(
            f"The task function must return a dict with 'input' and "
            f"'output' keys, but the returned dict is missing: "
            f"{missing}. Got keys: {set(result.keys())}. "
            f"Example: return {{'input': data, 'output': response}}"
        )
    return result


class EvaluationSuite:
    """
    A pre-configured regression test suite for LLM applications.

    Evaluation Suites let you:
    - Define test cases with inputs and context
    - Configure assertions that will be checked by an LLM
    - Run tests against any task function

    Suite-level assertions and execution policy are stored in the dataset's
    metadata and read by the evaluation engine when running the suite.

    Example:
        >>> from opik import Opik
        >>>
        >>> client = Opik()
        >>>
        >>> suite = client.create_evaluation_suite(
        ...     name="Refund Policy Tests",
        ...     description="Regression tests for refund scenarios",
        ...     assertions=[
        ...         "Response does not contain hallucinated information",
        ...         "Response is helpful to the user",
        ...     ],
        ... )
        >>>
        >>> suite.add_item(
        ...     data={"user_input": "How do I get a refund?", "user_tier": "premium"},
        ...     assertions=["Response is polite"],
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
        Internal constructor — not part of the public API.

        Use :meth:`opik.Opik.create_evaluation_suite` or
        :meth:`opik.Opik.get_or_create_evaluation_suite` instead.
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

    def get_tags(self) -> List[str]:
        """
        Get the tags for the suite.

        Returns:
            List of tag strings.
        """
        return self._dataset.get_tags()

    def get_items(self) -> List[Dict[str, Any]]:
        """
        Retrieve suite items as a list of dictionaries.

        Each item dict has keys:
        - "id": the dataset item ID (str)
        - "data": the test case data (dict)
        - "description": optional item description (str or None)
        - "assertions": list of assertion strings (or empty list)
        - "execution_policy": ExecutionPolicyItem or None

        Returns:
            A list of item dictionaries.
        """
        from opik.evaluation.suite_evaluators import llm_judge
        from opik.evaluation.suite_evaluators.llm_judge import (
            config as llm_judge_config,
        )

        result = []
        for item in self._dataset.__internal_api__stream_items_as_dataclasses__():
            item_assertions: list[str] = []
            if item.evaluators:
                for e in item.evaluators:
                    if e.type == "llm_judge":
                        cfg = llm_judge_config.LLMJudgeConfig(**e.config)
                        judge = llm_judge.LLMJudge.from_config(cfg)
                        item_assertions.extend(judge.assertions)

            result.append(
                {
                    "id": item.id,
                    "data": item.get_content(),
                    "description": item.description,
                    "assertions": item_assertions,
                    "execution_policy": item.execution_policy,
                }
            )
        return result

    def update(
        self,
        *,
        execution_policy: Optional[execution_policy.ExecutionPolicy] = None,
        assertions: Optional[List[str]] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        """
        Update the suite-level assertions, execution policy, and/or tags.

        Supports partial updates: any parameter not provided will retain
        its current value.

        Args:
            execution_policy: New execution policy for the suite.
                If not provided, the current policy is kept.
            assertions: New suite-level assertions. Each string describes
                an expected behavior that will be checked by an LLM.
                If not provided, the current assertions are kept.
            tags: Tags for the suite.

        Raises:
            ValueError: If nothing to update is provided.
        """
        if execution_policy is not None:
            validators.validate_execution_policy(execution_policy)

        resolved = validators.resolve_evaluators(
            assertions, None, "suite-level assertions"
        )

        if resolved is None and execution_policy is None and tags is None:
            raise ValueError(
                "At least one of 'assertions', "
                "'execution_policy', or 'tags' must be provided."
            )

        # Tags are a dataset-level field, so they're updated separately
        # from assertions/execution_policy which are version-level.
        if tags is not None:
            self._dataset._rest_client.datasets.update_dataset(
                id=self._dataset.id,
                name=self._name,
                tags=tags,
            )

        has_version_updates = resolved is not None or execution_policy is not None
        if has_version_updates:
            version_info = self._dataset.get_version_info()
            if version_info is None:
                raise RuntimeError(
                    f"Cannot update evaluation suite '{self._name}': "
                    "no version info found. Add at least one item first."
                )

            if resolved is None:
                resolved = self._dataset.get_evaluators()
            if execution_policy is None:
                execution_policy = self.get_execution_policy()

            rest_operations.update_evaluation_suite_dataset(
                rest_client=self._dataset._rest_client,
                dataset_id=self._dataset.id,
                base_version_id=version_info.id,
                evaluators=resolved,
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

    def get_assertions(self) -> List[str]:
        """
        Get the suite-level assertions.

        Returns:
            List of assertion strings.
        """
        evaluators = self._dataset.get_evaluators()
        assertions: list[str] = []
        for evaluator in evaluators:
            assertions.extend(evaluator.assertions)
        return assertions

    def add_item(
        self,
        data: Dict[str, Any],
        *,
        assertions: Optional[List[str]] = None,
        description: Optional[str] = None,
        execution_policy: Optional[execution_policy.ExecutionPolicy] = None,
    ) -> None:
        """
        Add a test case to the evaluation suite.

        Args:
            data: Dictionary containing the test case data. This is passed to
                the task function and can contain any fields needed.
                Example: {"user_input": "How do I get a refund?", "user_tier": "premium"}
            assertions: Item-specific assertions. Each string describes an
                expected behavior that will be checked by an LLM.
            description: Optional description of this test case.
            execution_policy: Item-specific execution policy override.
                Example: {"runs_per_item": 3, "pass_threshold": 2}

        Example:
            >>> suite.add_item(
            ...     data={"user_input": "How do I get a refund?", "user_tier": "premium"},
            ...     description="Test refund request from premium user",
            ...     assertions=["Response is polite"],
            ... )
        """
        if execution_policy is not None:
            validators.validate_execution_policy(execution_policy)

        evaluators = validators.resolve_evaluators(
            assertions, None, "item-level assertions"
        )

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
            description=description,
            evaluators=evaluator_items,
            execution_policy=execution_policy_item,
            **data,
        )
        self._dataset.__internal_api__insert_items_as_dataclasses__([ds_item])

    def add_items(
        self,
        items: List[suite_types.EvaluationSuiteItem],
    ) -> None:
        """
        Add multiple test cases to the evaluation suite in a single batch.

        This is more efficient than calling add_item() repeatedly, as it
        creates only one dataset version for the entire batch.

        Args:
            items: List of test case items to add.

        Example:
            >>> suite.add_items([
            ...     {"data": {"question": "How do I get a refund?"}},
            ...     {
            ...         "data": {"question": "Is my account hacked?"},
            ...         "assertions": ["Response treats the concern with urgency"],
            ...         "execution_policy": {"runs_per_item": 5, "pass_threshold": 4},
            ...     },
            ... ])
        """
        validators.validate_suite_items(items)

        ds_items: List[dataset_item.DatasetItem] = []
        for item in items:
            evaluators = validators.resolve_evaluators(
                item.get("assertions"), None, "item-level assertions"
            )

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

            ep = item.get("execution_policy")
            execution_policy_item = None
            if ep:
                execution_policy_item = dataset_item.ExecutionPolicyItem(
                    runs_per_item=ep.get("runs_per_item"),
                    pass_threshold=ep.get("pass_threshold"),
                )

            ds_items.append(
                dataset_item.DatasetItem(
                    id=id_helpers.generate_id(),
                    description=item.get("description"),
                    evaluators=evaluator_items,
                    execution_policy=execution_policy_item,
                    **item["data"],
                )
            )

        self._dataset.__internal_api__insert_items_as_dataclasses__(ds_items)

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
        verbose: int = 2,
        worker_threads: int = 16,
        model: Optional[str] = None,
    ) -> suite_types.EvaluationSuiteResult:
        """
        Run the evaluation suite against a task function.

        The task function receives each test item's data dict and must return
        a dict with "input" and "output" keys.

        Args:
            task: A callable that takes a dict (the item's data) and returns
                a dict with "input" and "output" keys.
            experiment_name_prefix: Optional prefix for auto-generated experiment name.
            experiment_name: Optional explicit name for the experiment.
            project_name: Optional project name for tracking.
            experiment_config: Optional configuration dict for the experiment.
            prompts: Optional list of Prompt objects to associate with the experiment.
            experiment_tags: Optional list of tags to associate with the experiment.
            verbose: Verbosity level. 0=silent, 1=summary only (pass/fail,
                items passed, pass rate), 2=summary + per-assertion pass rates
                (default).
            worker_threads: Number of threads for parallel task execution.
            model: Optional model name to use for checking assertions.
                If not provided, uses the default model (gpt-5-nano).

        Returns:
            EvaluationSuiteResult with pass/fail status based on execution policy.

        Example:
            >>> def my_llm_task(data: dict) -> dict:
            ...     response = call_my_llm(data["user_input"], user_tier=data.get("user_tier"))
            ...     return {"input": data, "output": response}
            >>>
            >>> result = suite.run(task=my_llm_task, model="gpt-4o")
            >>> print(f"Suite passed: {result.passed}")
            >>> print(f"Items passed: {result.items_passed}/{result.items_total}")
        """
        return self.__internal_api__run_optimization_suite__(
            task=task,
            experiment_name_prefix=experiment_name_prefix,
            experiment_name=experiment_name,
            project_name=project_name,
            experiment_config=experiment_config,
            prompts=prompts,
            experiment_tags=experiment_tags,
            verbose=verbose,
            worker_threads=worker_threads,
            model=model,
        )

    def __internal_api__run_optimization_suite__(
        self,
        task: LLMTask,
        *,
        experiment_name_prefix: Optional[str] = None,
        experiment_name: Optional[str] = None,
        project_name: Optional[str] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        prompts: Optional[List[base_prompt.BasePrompt]] = None,
        experiment_tags: Optional[List[str]] = None,
        verbose: int = 2,
        worker_threads: int = 16,
        model: Optional[str] = None,
        optimization_id: Optional[str] = None,
        experiment_type: Optional[str] = None,
        dataset_item_ids: Optional[List[str]] = None,
        dataset_filter_string: Optional[str] = None,
        client: Optional[Any] = None,
    ) -> suite_types.EvaluationSuiteResult:
        """
        Run the evaluation suite with optimization-specific parameters.

        This is the internal entry point used by the optimizer framework.
        It extends the public ``run()`` method with parameters for linking
        experiments to optimization runs, filtering dataset items, and
        injecting a pre-configured Opik client.

        Args:
            task: A callable that takes a dict (the item's data) and returns
                a dict with "input" and "output" keys.
            experiment_name_prefix: Optional prefix for auto-generated experiment name.
            experiment_name: Optional explicit name for the experiment.
            project_name: Optional project name for tracking.
            experiment_config: Optional configuration dict for the experiment.
            prompts: Optional list of Prompt objects to associate with the experiment.
            experiment_tags: Optional list of tags to associate with the experiment.
            verbose: Verbosity level. 0=silent, 1=summary only, 2=detailed (default).
            worker_threads: Number of threads for parallel task execution.
            model: Optional model name to use for checking assertions.
            optimization_id: Optimization ID to link the experiment to.
            experiment_type: Experiment type (e.g. "trial", "mini-batch").
            dataset_item_ids: Subset of dataset item IDs to evaluate.
            dataset_filter_string: OQL filter string to filter dataset items.
            client: Opik client instance. If not provided, uses the cached client.

        Returns:
            EvaluationSuiteResult with pass/fail status based on execution policy.
        """
        from opik.evaluation import evaluator as opik_evaluator

        @functools.wraps(task)
        def _validated_task(data: Dict[str, Any]) -> Any:
            return validate_task_result(task(data))

        return opik_evaluator.evaluate_suite(
            dataset=self._dataset,
            task=_validated_task,
            client=client,
            dataset_item_ids=dataset_item_ids,
            dataset_filter_string=dataset_filter_string,
            experiment_name_prefix=experiment_name_prefix,
            experiment_name=experiment_name,
            project_name=project_name,
            experiment_config=experiment_config,
            prompts=prompts,
            experiment_tags=experiment_tags,
            verbose=verbose,
            task_threads=worker_threads,
            evaluator_model=model,
            optimization_id=optimization_id,
            experiment_type=experiment_type,
        )
