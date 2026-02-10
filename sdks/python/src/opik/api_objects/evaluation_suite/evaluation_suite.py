"""
Evaluation Suite API for regression testing LLM applications.

Evaluation Suites are pre-configured regression test suites that let you
validate that prompt changes, model updates, or code modifications don't
break existing functionality.
"""

import logging
from typing import Any, Callable, Dict, List, Optional, TYPE_CHECKING

from opik.api_objects.prompt import base_prompt

from . import types

if TYPE_CHECKING:
    from opik.evaluation import suite_evaluators

LOGGER = logging.getLogger(__name__)

# Type alias for the task function
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
        description: Optional[str] = None,
        evaluators: Optional[List["suite_evaluators.LLMJudge"]] = None,
        execution_policy: Optional[types.ExecutionPolicy] = None,
    ):
        self._name = name
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
            NotImplementedError: Backend integration not yet available.

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

        # TODO: Implement when backend is merged
        # This will:
        # 1. Fetch evaluator configs from backend (dataset_evaluators table)
        # 2. For each item, run task runs_per_item times
        # 3. Score each run with evaluators
        # 4. Determine pass/fail based on execution policy
        raise NotImplementedError(
            "Backend integration not yet available. "
            "This API is ready for use once the backend is merged."
        )
