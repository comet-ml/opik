from typing import Any, Callable, Dict, List, Optional, TYPE_CHECKING
import dataclasses

from opik.evaluation.metrics import score_result

if TYPE_CHECKING:
    from opik.evaluation import suite_evaluators


@dataclasses.dataclass
class ExecutionPolicy:
    """
    Execution policy for handling LLM non-determinism.

    Attributes:
        runs_per_item: Number of times to run each test item. Defaults to 1.
        pass_threshold: Minimum number of runs that must pass for the item
            to be considered passed. Defaults to 1.
    """

    runs_per_item: int = 1
    pass_threshold: int = 1


@dataclasses.dataclass
class SuiteItem:
    """
    Represents a single test case in an evaluation suite.

    Each item defines a scenario to test, with input data and optional
    item-specific evaluators that override or extend suite-level evaluators.

    Attributes:
        data: Dictionary containing the test case data. This is passed to the
            task function and can contain any fields needed (e.g., user_input,
            context, messages, etc.).
            Example: {"user_input": "How do I get a refund?", "user_tier": "premium"}
        description: Optional human-readable description of what this test case covers.
            Example: "User asks about refund policy"
        evaluators: Optional item-specific evaluators. If provided, these
            are used instead of or in addition to suite-level evaluators.
        execution_policy: Optional item-specific execution policy override.
        metadata: Optional metadata (tags, source info, priority, etc.).
    """

    data: Dict[str, Any]
    description: Optional[str] = None
    evaluators: Optional[List["suite_evaluators.LLMJudge"]] = None
    execution_policy: Optional[ExecutionPolicy] = None
    metadata: Optional[Dict[str, Any]] = None


@dataclasses.dataclass
class SuiteItemRunResult:
    """
    Result of a single run of a suite item.

    When execution_policy.runs_per_item > 1, each item may have multiple
    run results to account for LLM non-determinism.

    Attributes:
        run_index: The index of this run (0-based).
        output: The output produced by the task for this run.
        score_results: List of ScoreResult objects from all evaluators.
        passed: Whether all metrics passed for this run.
        trace_id: The trace ID associated with this run.
    """

    run_index: int
    output: Any
    score_results: List[score_result.ScoreResult]
    passed: bool
    trace_id: str


@dataclasses.dataclass
class SuiteItemResult:
    """
    Result of evaluating a single suite item across all runs.

    Attributes:
        item: The original SuiteItem that was evaluated.
        run_results: List of results for each run of this item.
        passed: Whether the item passed based on execution policy threshold.
        passed_runs: Number of runs that passed.
        total_runs: Total number of runs executed.
    """

    item: SuiteItem
    run_results: List[SuiteItemRunResult]
    passed: bool
    passed_runs: int
    total_runs: int


@dataclasses.dataclass
class SuiteResult:
    """
    Result of running an evaluation suite.

    Attributes:
        experiment_id: The ID of the created experiment.
        experiment_name: The name of the experiment.
        experiment_url: URL to view the experiment in the Opik UI.
        item_results: List of results for each evaluated item.
        passed_items: Number of items that passed.
        total_items: Total number of items evaluated.
        passed: Whether the entire suite passed (all items passed).
    """

    experiment_id: str
    experiment_name: Optional[str]
    experiment_url: Optional[str]
    item_results: List[SuiteItemResult]
    passed_items: int
    total_items: int
    passed: bool

    def get_all_scores(self) -> List[score_result.ScoreResult]:
        """Get all score results from all items and runs."""
        all_scores: List[score_result.ScoreResult] = []
        for item_result in self.item_results:
            for run_result in item_result.run_results:
                all_scores.extend(run_result.score_results)
        return all_scores

    def get_pass_rate(self) -> float:
        """Calculate the overall item pass rate."""
        if self.total_items == 0:
            return 0.0
        return self.passed_items / self.total_items
