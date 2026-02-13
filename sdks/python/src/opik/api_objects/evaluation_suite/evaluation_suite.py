"""
Evaluation Suite API for regression testing LLM applications.

Evaluation Suites are pre-configured regression test suites that let you
validate that prompt changes, model updates, or code modifications don't
break existing functionality.
"""

import logging
from typing import Any, Callable, Dict, List, Optional

from opik import id_helpers
from opik.api_objects.prompt import base_prompt
from opik.api_objects.dataset import dataset, dataset_item
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation.engine import engine as evaluation_engine
from opik.evaluation.engine import types as engine_types
from opik.evaluation.suite_evaluators import llm_judge

from . import suite_result_constructor
from . import types as suite_types


LOGGER = logging.getLogger(__name__)

LLMTask = Callable[[Dict[str, Any]], Any]


def _validate_evaluators(evaluators: List[Any], context: str) -> None:
    """
    Validate that all evaluators are LLMJudge instances.

    Args:
        evaluators: List of evaluators to validate.
        context: Description of where the evaluators are being used (for error message).

    Raises:
        TypeError: If any evaluator is not an LLMJudge instance.
    """
    for evaluator in evaluators:
        if not isinstance(evaluator, llm_judge.LLMJudge):
            raise TypeError(
                f"Evaluation suites only support LLMJudge evaluators. "
                f"Got {type(evaluator).__name__} in {context}. "
                f"Use LLMJudge from opik.evaluation.suite_evaluators instead."
            )


class EvaluationSuite:
    """
    A pre-configured regression test suite for LLM applications.

    Evaluation Suites let you:
    - Define test cases with inputs and context
    - Configure evaluation criteria (LLMJudge evaluators only)
    - Run tests against any task function

    The suite stores evaluator configurations in dataset items, making them
    reusable across multiple test runs without code changes.

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
        description: Optional[str] = None,
        evaluators: Optional[List[llm_judge.LLMJudge]] = None,
        execution_policy: Optional[evaluation_engine.ExecutionPolicy] = None,
    ):
        if evaluators:
            _validate_evaluators(evaluators, "suite-level evaluators")

        self._name = name
        self._dataset = dataset_
        self._description = description
        # Dataset-level evaluators and execution_policy are stored in memory for now.
        # Once OPIK-4222/4223 are implemented, these will be stored as dataset columns
        # and retrieved from the backend.
        self._evaluators = evaluators or []
        self._execution_policy: evaluation_engine.ExecutionPolicy = (
            execution_policy
            if execution_policy
            else evaluation_engine.DEFAULT_EXECUTION_POLICY.copy()
        )

    @property
    def name(self) -> str:
        """The name of the evaluation suite."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the evaluation suite."""
        return self._description

    @property
    def evaluators(self) -> List[llm_judge.LLMJudge]:
        """Suite-level evaluators applied to all items."""
        return self._evaluators

    @property
    def dataset(self) -> dataset.Dataset:
        """The underlying dataset storing suite items."""
        return self._dataset

    @property
    def execution_policy(self) -> evaluation_engine.ExecutionPolicy:
        """Dataset-level execution policy."""
        return self._execution_policy

    def add_item(
        self,
        data: Dict[str, Any],
        evaluators: Optional[List[llm_judge.LLMJudge]] = None,
        execution_policy: Optional[evaluation_engine.ExecutionPolicy] = None,
    ) -> "EvaluationSuite":
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

        Returns:
            Self for method chaining.

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
            _validate_evaluators(evaluators, "item-level evaluators")

        item_id = id_helpers.generate_id()

        item_content = dict(data)

        # Store evaluation config (evaluators and execution_policy) under a single key
        eval_config: Dict[str, Any] = {}
        if evaluators:
            eval_config["evaluators"] = [e.to_config().model_dump() for e in evaluators]
        if execution_policy:
            eval_config["execution_policy"] = execution_policy
        if eval_config:
            item_content[engine_types.EVALUATION_CONFIG_KEY] = eval_config

        ds_item = dataset_item.DatasetItem(id=item_id, **item_content)
        self._dataset.__internal_api__insert_items_as_dataclasses__([ds_item])

        return self

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

        Returns:
            EvaluationSuiteResult with pass/fail status based on execution policy.

        Example:
            >>> def my_llm_task(data: dict) -> dict:
            ...     response = call_my_llm(data["user_input"], user_tier=data.get("user_tier"))
            ...     return {"input": data, "output": response}
            >>>
            >>> result = suite.run(task=my_llm_task)
            >>> print(f"Suite passed: {result.passed}")
            >>> print(f"Items passed: {result.items_passed}/{result.items_total}")
        """
        # Evaluation suites use execution_policy.runs_per_item instead of trial_count.
        # Set execution_policy on the dataset so the engine can extract it.
        # This is a temporary approach until OPIK-4222/4223 adds proper backend support.
        self._dataset.execution_policy = self._execution_policy  # type: ignore[attr-defined]

        eval_result = opik_evaluator.evaluate(
            dataset=self._dataset,
            task=task,
            scoring_metrics=list(self._evaluators),
            experiment_name_prefix=experiment_name_prefix,
            experiment_name=experiment_name,
            project_name=project_name,
            experiment_config=experiment_config,
            prompts=prompts,
            experiment_tags=experiment_tags,
            verbose=verbose,
            task_threads=worker_threads,
        )

        return suite_result_constructor.build_suite_result(eval_result)
