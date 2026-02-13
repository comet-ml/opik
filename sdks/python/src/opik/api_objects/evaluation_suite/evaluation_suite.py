"""
Evaluation Suite API for regression testing LLM applications.

Evaluation Suites are pre-configured regression test suites that let you
validate that prompt changes, model updates, or code modifications don't
break existing functionality.
"""

import dataclasses
import logging
from collections import defaultdict
from typing import Any, Callable, Dict, List, Optional, TYPE_CHECKING

from opik import id_helpers
from opik.api_objects.prompt import base_prompt
from opik.api_objects.dataset import dataset, dataset_item
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation import evaluation_result, test_result
from opik.evaluation.engine import ExecutionPolicy, DEFAULT_EXECUTION_POLICY
from opik.evaluation.suite_evaluators import llm_judge


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

# Reserved keys for evaluation suite metadata stored in dataset item content.
# These will become proper backend fields on Dataset and DatasetItem once
# OPIK-4222/4223 are implemented. At that point:
# - Dataset will have `evaluators` and `execution_policy` columns
# - DatasetItem will have `evaluators` and `execution_policy` columns
# For now, we store item-level configs in the item content under these keys.
EVALUATORS_KEY = "__evaluators__"
EXECUTION_POLICY_KEY = "__execution_policy__"


@dataclasses.dataclass
class ItemResult:
    """Result for a single evaluation suite item."""

    dataset_item_id: str
    """The ID of the dataset item."""

    passed: bool
    """Whether this item passed based on its execution policy."""

    runs_passed: int
    """Number of runs that passed for this item."""

    runs_total: int
    """Total number of runs for this item."""

    pass_threshold: int
    """Minimum passing runs required (from execution policy)."""

    test_results: List[test_result.TestResult]
    """Individual test results for each run of this item."""


@dataclasses.dataclass
class EvaluationSuiteResult:
    """
    Result of running an evaluation suite.

    Contains pass/fail status for each item based on execution policy,
    as well as overall suite pass/fail status.

    Attributes:
        passed: Whether the entire suite passed (all items passed).
        items_passed: Number of items that passed.
        items_total: Total number of items evaluated.
        item_results: Results for each individual item.
        evaluation_result: The underlying EvaluationResult with full details.
    """

    passed: bool
    """Whether the entire suite passed (all items passed)."""

    items_passed: int
    """Number of items that passed."""

    items_total: int
    """Total number of items evaluated."""

    item_results: Dict[str, ItemResult]
    """Results for each item, keyed by dataset_item_id."""

    evaluation_result: evaluation_result.EvaluationResult
    """The underlying EvaluationResult with full experiment details."""

    @property
    def experiment_id(self) -> str:
        """The experiment ID."""
        return self.evaluation_result.experiment_id

    @property
    def experiment_name(self) -> Optional[str]:
        """The experiment name."""
        return self.evaluation_result.experiment_name

    @property
    def experiment_url(self) -> Optional[str]:
        """URL to view the experiment."""
        return self.evaluation_result.experiment_url


def _build_suite_result(
    eval_result: evaluation_result.EvaluationResult,
) -> EvaluationSuiteResult:
    """
    Build an EvaluationSuiteResult from an EvaluationResult.

    Groups test results by dataset item and computes pass/fail status
    based on execution policies stored in each item's content.
    """
    results_by_item: Dict[str, List[test_result.TestResult]] = defaultdict(list)
    item_contents: Dict[str, Dict[str, Any]] = {}

    for result in eval_result.test_results:
        item_id = result.test_case.dataset_item_id
        results_by_item[item_id].append(result)
        if item_id not in item_contents:
            item_contents[item_id] = result.test_case.dataset_item_content

    item_results: Dict[str, ItemResult] = {}
    items_passed = 0

    for item_id, item_test_results in results_by_item.items():
        item_content = item_contents.get(item_id, {})
        item_policy = item_content.get(EXECUTION_POLICY_KEY, {})
        pass_threshold = item_policy.get("pass_threshold", 1)

        runs_passed = sum(
            1 for r in item_test_results
            if not r.score_results or all(
                s.value >= 0.5 if isinstance(s.value, (int, float)) else bool(s.value)
                for s in r.score_results
            )
        )

        passed = runs_passed >= pass_threshold

        if passed:
            items_passed += 1

        item_results[item_id] = ItemResult(
            dataset_item_id=item_id,
            passed=passed,
            runs_passed=runs_passed,
            runs_total=len(item_test_results),
            pass_threshold=pass_threshold,
            test_results=sorted(item_test_results, key=lambda r: r.trial_id),
        )

    return EvaluationSuiteResult(
        passed=items_passed == len(results_by_item),
        items_passed=items_passed,
        items_total=len(results_by_item),
        item_results=item_results,
        evaluation_result=eval_result,
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
        execution_policy: Optional[ExecutionPolicy] = None,
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
        self._execution_policy: ExecutionPolicy = (
            execution_policy if execution_policy else DEFAULT_EXECUTION_POLICY.copy()
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
    def execution_policy(self) -> ExecutionPolicy:
        """Dataset-level execution policy."""
        return self._execution_policy

    def add_item(
        self,
        data: Dict[str, Any],
        evaluators: Optional[List[llm_judge.LLMJudge]] = None,
        execution_policy: Optional[ExecutionPolicy] = None,
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

        if evaluators:
            item_content[EVALUATORS_KEY] = [e.to_config().model_dump() for e in evaluators]

        if execution_policy:
            item_content[EXECUTION_POLICY_KEY] = execution_policy

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
    ) -> EvaluationSuiteResult:
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

        return _build_suite_result(eval_result)
