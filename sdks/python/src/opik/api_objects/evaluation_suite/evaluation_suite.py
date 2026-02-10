"""
Evaluation Suite API for regression testing LLM applications.

Evaluation Suites are pre-configured regression test suites that let you
validate that prompt changes, model updates, or code modifications don't
break existing functionality.
"""

import logging
from typing import Any, Callable, Dict, List, Optional, TYPE_CHECKING

from opik.api_objects.prompt import base_prompt
from opik.api_objects.dataset import dataset as dataset_module
from opik.evaluation import evaluator as opik_evaluator
from opik.evaluation.metrics import score_result

from . import types

if TYPE_CHECKING:
    from opik.evaluation import suite_evaluators

LOGGER = logging.getLogger(__name__)

SUITE_ITEM_INDEX_KEY = "__suite_item_index__"

LLMTask = Callable[[Dict[str, Any]], Any]


class EvaluationSuite:
    """
    A pre-configured regression test suite for LLM applications.

    Evaluation Suites let you:
    - Define test cases with inputs and context
    - Configure evaluation criteria (LLMJudge evaluators)
    - Run tests against any task function
    - Handle LLM non-determinism with multi-run execution

    The suite stores evaluator configurations in the backend, making them
    reusable across multiple test runs without code changes.

    Args:
        name: Name for the evaluation suite.
        description: Optional description of what this suite tests.
        evaluators: Suite-level evaluators applied to all items.
        execution_policy: Default execution policy for all items.
            Controls runs_per_item and pass_threshold.

    Example:
        >>> from opik import Opik
        >>> from opik.evaluation.suite_evaluators import LLMJudge
        >>>
        >>> client = Opik()
        >>>
        >>> suite_evaluator = LLMJudge(
        ...     assertions=[
        ...         {"name": "no_hallucination", "expected_behavior": "Response does not contain hallucinated information"},
        ...         {"name": "helpful", "expected_behavior": "Response is helpful to the user"},
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
        ...     description="Premium user refund request",
        ...     evaluators=[
        ...         LLMJudge(assertions=[{"name": "polite", "expected_behavior": "Response is polite"}]),
        ...     ]
        ... )
        >>>
        >>> results = suite.run(task=my_llm_function)
        >>> print(f"Passed: {results.passed_items}/{results.total_items}")
    """

    def __init__(
        self,
        name: str,
        dataset: dataset_module.Dataset,
        description: Optional[str] = None,
        evaluators: Optional[List["suite_evaluators.LLMJudge"]] = None,
        execution_policy: Optional[types.ExecutionPolicy] = None,
    ):
        self._name = name
        self._dataset = dataset
        self._description = description
        self._evaluators = evaluators or []
        self._execution_policy = execution_policy or types.ExecutionPolicy()
        self._items: List[types.SuiteItem] = []

    @property
    def name(self) -> str:
        """The name of the evaluation suite."""
        return self._name

    @property
    def description(self) -> Optional[str]:
        """The description of the evaluation suite."""
        return self._description

    @property
    def evaluators(self) -> List["suite_evaluators.LLMJudge"]:
        """Suite-level evaluators applied to all items."""
        return self._evaluators

    @property
    def execution_policy(self) -> types.ExecutionPolicy:
        """Default execution policy for all items."""
        return self._execution_policy

    @property
    def items(self) -> List[types.SuiteItem]:
        """The list of test items in this suite."""
        return self._items

    @property
    def dataset(self) -> dataset_module.Dataset:
        """The underlying dataset storing suite items."""
        return self._dataset

    def add_item(
        self,
        data: Dict[str, Any],
        description: Optional[str] = None,
        evaluators: Optional[List["suite_evaluators.LLMJudge"]] = None,
        execution_policy: Optional[types.ExecutionPolicy] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> "EvaluationSuite":
        """
        Add a test case to the evaluation suite.

        Each test case represents a scenario to validate. The item's evaluators
        can override or extend suite-level evaluators.

        Args:
            data: Dictionary containing the test case data. This is passed to
                the task function and can contain any fields needed.
                Example: {"user_input": "How do I get a refund?", "user_tier": "premium"}
            description: Optional human-readable description of this test case.
                Example: "User asks about refund policy"
            evaluators: Item-specific evaluators. If provided, these are used
                for this item instead of suite-level evaluators.
            execution_policy: Item-specific execution policy override.
                Use for critical tests that need more runs or stricter thresholds.
            metadata: Optional metadata (tags, source info, priority, etc.).

        Returns:
            Self for method chaining.

        Example:
            >>> suite.add_item(
            ...     data={"user_input": "How do I get a refund?", "user_tier": "premium"},
            ...     description="Premium user refund request",
            ...     evaluators=[
            ...         LLMJudge(assertions=[{"name": "polite", "expected_behavior": "Response is polite"}]),
            ...     ]
            ... )
        """
        item = types.SuiteItem(
            data=data,
            description=description,
            evaluators=evaluators,
            execution_policy=execution_policy,
            metadata=metadata,
        )
        self._items.append(item)

        dataset_item = dict(data)
        dataset_item[SUITE_ITEM_INDEX_KEY] = len(self._items) - 1
        self._dataset.insert([dataset_item])

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
    ) -> types.SuiteResult:
        """
        Run the evaluation suite against a task function.

        The task function receives each test item's data dict and should
        return the LLM output to evaluate.

        Args:
            task: A callable that takes a dict (the item's data) and returns
                the LLM output.
            experiment_name_prefix: Optional prefix for auto-generated experiment name.
            experiment_name: Optional explicit name for the experiment.
            project_name: Optional project name for tracking.
            experiment_config: Optional configuration dict for the experiment.
            prompts: Optional list of Prompt objects to associate with the experiment.
            experiment_tags: Optional list of tags to associate with the experiment.
            verbose: Verbosity level (0=silent, 1=normal, 2=detailed).
            worker_threads: Number of threads for parallel task execution.

        Returns:
            SuiteResult with pass/fail status for all items.

        Raises:
            ValueError: If no items have been added to the suite.

        Example:
            >>> def my_llm_task(data: dict) -> str:
            ...     return call_my_llm(data["user_input"], user_tier=data.get("user_tier"))
            >>>
            >>> results = suite.run(task=my_llm_task)
            >>> print(f"Passed: {results.passed_items}/{results.total_items}")
        """
        if not self._items:
            raise ValueError(
                "No items added to the evaluation suite. Use add_item() first."
            )

        scoring_metrics = self._get_scoring_metrics()

        def wrapped_task(item: Dict[str, Any]) -> Dict[str, Any]:
            task_input = {k: v for k, v in item.items() if k != SUITE_ITEM_INDEX_KEY}
            output = task(task_input)
            return {"output": output, "input": task_input}

        eval_result = opik_evaluator.evaluate(
            dataset=self._dataset,
            task=wrapped_task,
            scoring_metrics=scoring_metrics,
            experiment_name_prefix=experiment_name_prefix,
            experiment_name=experiment_name,
            project_name=project_name,
            experiment_config=experiment_config,
            prompts=prompts,
            experiment_tags=experiment_tags,
            verbose=verbose,
            task_threads=worker_threads,
        )

        return self._convert_to_suite_result(eval_result)

    def _get_scoring_metrics(self) -> List["suite_evaluators.LLMJudge"]:
        """Get all scoring metrics (evaluators) for the suite."""
        return list(self._evaluators)

    def _convert_to_suite_result(
        self,
        eval_result: Any,
    ) -> types.SuiteResult:
        """Convert EvaluationResult to SuiteResult."""
        from opik.evaluation import test_result as test_result_module

        results_by_item: Dict[int, List[test_result_module.TestResult]] = {}
        for test_result in eval_result.test_results:
            item_index = test_result.test_case.dataset_item_content.get(
                SUITE_ITEM_INDEX_KEY, 0
            )
            if item_index not in results_by_item:
                results_by_item[item_index] = []
            results_by_item[item_index].append(test_result)

        item_results: List[types.SuiteItemResult] = []
        passed_items = 0

        for index, item in enumerate(self._items):
            test_results = results_by_item.get(index, [])

            run_results: List[types.SuiteItemRunResult] = []
            for test_result in test_results:
                all_passed = all(
                    self._score_passed(sr) for sr in test_result.score_results
                )
                run_result = types.SuiteItemRunResult(
                    run_index=test_result.trial_id,
                    output=test_result.test_case.task_output,
                    score_results=test_result.score_results,
                    passed=all_passed,
                    trace_id=test_result.test_case.trace_id or "",
                )
                run_results.append(run_result)

            passed_runs = sum(1 for r in run_results if r.passed)
            total_runs = len(run_results)
            item_passed = passed_runs >= 1 if total_runs > 0 else False

            if item_passed:
                passed_items += 1

            item_result = types.SuiteItemResult(
                item=item,
                run_results=run_results,
                passed=item_passed,
                passed_runs=passed_runs,
                total_runs=total_runs,
            )
            item_results.append(item_result)

        return types.SuiteResult(
            experiment_id=eval_result.experiment_id,
            experiment_name=eval_result.experiment_name,
            experiment_url=eval_result.experiment_url,
            item_results=item_results,
            passed_items=passed_items,
            total_items=len(self._items),
            passed=passed_items == len(self._items),
        )

    def _score_passed(self, score: score_result.ScoreResult) -> bool:
        """Determine if a score result indicates a pass."""
        if isinstance(score.value, bool):
            return score.value
        if isinstance(score.value, (int, float)):
            return score.value >= 0.5
        return False
