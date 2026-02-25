import threading
from collections import defaultdict
from concurrent import futures
from typing import Any, Dict, List, Optional, TypeVar, Generic

from ..metrics.score_result import ScoreResult

from .types import EvaluationTask

T = TypeVar("T")


class StreamingExecutor(Generic[T]):
    """
    Executor that accepts and processes evaluation tasks incrementally using a thread pool.

    Tasks can be submitted one at a time and will begin executing immediately, allowing
    for streaming behavior regardless of the number of workers configured.

    Progress bar updates happen immediately as tasks complete via future callbacks,
    even while the main thread is still submitting new tasks.
    """

    def __init__(
        self,
        workers: int,
        verbose: int,
        desc: str = "Evaluation",
        total: Optional[int] = None,
        show_score_postfix: bool = True,
    ):
        self._workers = workers
        self._verbose = verbose
        self._desc = desc
        self._total = total
        self._show_score_postfix = show_score_postfix
        self._task_count = 0
        self._pool: futures.ThreadPoolExecutor
        self._submitted_futures: List[futures.Future[T]] = []
        self._progress_bar: Optional[Any] = None
        self._future_to_group: Dict[futures.Future[T], str] = {}
        self._group_sizes: Dict[str, int] = {}

        # Callback state — accessed from worker threads under lock
        self._lock = threading.Lock()
        self._results: List[T] = []
        self._score_totals: Dict[str, float] = defaultdict(float)
        self._score_counts: Dict[str, int] = defaultdict(int)
        self._group_completed: Dict[str, int] = defaultdict(int)

    def __enter__(self) -> "StreamingExecutor[T]":
        self._pool = futures.ThreadPoolExecutor(max_workers=self._workers)
        self._pool.__enter__()
        # Initialize progress bar on enter (lazy import for mockability)
        from opik.environment import get_tqdm_for_current_environment

        _tqdm = get_tqdm_for_current_environment()
        self._progress_bar = _tqdm(
            disable=(self._verbose < 1),
            desc=self._desc,
            total=self._total,
        )
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        # Close progress bar if it exists
        if self._progress_bar is not None:
            self._progress_bar.close()
        self._pool.__exit__(exc_type, exc_val, exc_tb)

    def set_group_size(self, group_id: str, size: int) -> None:
        """Declare the expected number of tasks for a group.

        Must be called before submitting tasks for that group to avoid
        race conditions between task completion callbacks and submission.
        """
        self._group_sizes[group_id] = size

    def submit(self, task: EvaluationTask[T], group_id: Optional[str] = None) -> None:
        """Submit a task to the thread pool for execution.

        Args:
            task: The evaluation task to execute.
            group_id: Optional group identifier. When provided, progress bar
                updates once per group (when all tasks in the group complete)
                instead of once per task. The group size must be declared
                via ``set_group_size()`` before submitting grouped tasks.
        """
        self._task_count += 1
        future = self._pool.submit(task)
        self._submitted_futures.append(future)

        if group_id is not None:
            self._future_to_group[future] = group_id

        future.add_done_callback(self._on_future_done)

    def _on_future_done(self, future: futures.Future[T]) -> None:
        """Callback fired by worker thread when a task completes."""
        if future.exception() is not None:
            return

        result = future.result()

        with self._lock:
            self._results.append(result)

            # Update running scores if result has score_results attribute
            if hasattr(result, "score_results") and isinstance(
                result.score_results, list
            ):
                for score in result.score_results:
                    if isinstance(score, ScoreResult) and not score.scoring_failed:
                        self._score_totals[score.name] += score.value
                        self._score_counts[score.name] += 1

                # Update progress bar with running averages
                if (
                    self._progress_bar is not None
                    and self._score_counts
                    and self._show_score_postfix
                ):
                    postfix_dict = {
                        name: f"{self._score_totals[name] / self._score_counts[name]:.4f}"
                        for name in self._score_counts
                    }
                    self._progress_bar.set_postfix(postfix_dict)

            # Update progress bar
            if self._progress_bar is not None:
                if future in self._future_to_group:
                    gid = self._future_to_group[future]
                    self._group_completed[gid] += 1
                    if self._group_completed[gid] == self._group_sizes[gid]:
                        self._progress_bar.update(1)
                else:
                    self._progress_bar.update(1)

    def get_results(self) -> List[T]:
        """Wait for all submitted tasks and return their results.

        Progress tracking happens via callbacks during both the submit and
        wait phases. This method sets the total if it wasn't known at
        construction, waits for completion, and re-raises any exceptions.
        """
        # Update total if it wasn't known initially
        if self._progress_bar is not None and self._total is None:
            use_groups = bool(self._future_to_group)
            if use_groups:
                self._progress_bar.total = len(self._group_sizes)
            else:
                self._progress_bar.total = self._task_count

        # Wait for all futures to complete
        futures.wait(self._submitted_futures)

        # Re-raise first exception if any task failed
        for future in self._submitted_futures:
            future.result()

        with self._lock:
            return list(self._results)


def execute(
    evaluation_tasks: List[EvaluationTask[T]],
    workers: int,
    verbose: int,
    desc: str = "Evaluation",
) -> List[T]:
    if workers == 1:
        from opik.environment import get_tqdm_for_current_environment

        _tqdm = get_tqdm_for_current_environment()
        test_results = [
            evaluation_task()
            for evaluation_task in _tqdm(
                evaluation_tasks,
                disable=(verbose < 1),
                desc=desc,
                total=len(evaluation_tasks),
            )
        ]

        return test_results

    with StreamingExecutor[T](
        workers=workers, verbose=verbose, desc=desc, total=len(evaluation_tasks)
    ) as executor:
        for evaluation_task in evaluation_tasks:
            executor.submit(evaluation_task)
        return executor.get_results()
