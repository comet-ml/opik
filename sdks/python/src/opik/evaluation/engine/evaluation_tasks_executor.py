from concurrent import futures
from typing import Any, List, TypeVar, Optional

from ...environment import get_tqdm_for_current_environment
from .types import EvaluationTask

_tqdm = get_tqdm_for_current_environment()

T = TypeVar("T")


class StreamingExecutor:
    """
    Executor that accepts tasks incrementally and processes them as they arrive.
    Useful for streaming scenarios where tasks are generated on-the-fly.
    """

    def __init__(self, workers: int, verbose: int, desc: str = "Evaluation"):
        self.workers = workers
        self.verbose = verbose
        self.desc = desc
        self.pool: Optional[futures.ThreadPoolExecutor] = None
        self.submitted_futures: List[futures.Future] = []
        self.task_count = 0

    def __enter__(self) -> "StreamingExecutor":
        if self.workers > 1:
            self.pool = futures.ThreadPoolExecutor(max_workers=self.workers)
            self.pool.__enter__()
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        if self.pool is not None:
            self.pool.__exit__(exc_type, exc_val, exc_tb)

    def submit(self, task: EvaluationTask[T]) -> None:
        """Submit a task for execution."""
        self.task_count += 1
        if self.workers == 1:
            # For single worker, we'll execute tasks synchronously when collecting results
            self.submitted_futures.append(task)  # type: ignore
        else:
            # For multiple workers, submit to thread pool
            future = self.pool.submit(task)  # type: ignore
            self.submitted_futures.append(future)

    def get_results(self) -> List[T]:
        """Collect all results from submitted tasks."""
        results: List[T] = []

        if self.workers == 1:
            # Execute tasks synchronously with progress bar
            for task in _tqdm(
                self.submitted_futures,  # type: ignore
                disable=(self.verbose < 1),
                desc=self.desc,
                total=self.task_count,
            ):
                results.append(task())  # type: ignore
        else:
            # Collect results from futures as they complete
            for future in _tqdm(
                futures.as_completed(self.submitted_futures),
                disable=(self.verbose < 1),
                desc=self.desc,
                total=self.task_count,
            ):
                results.append(future.result())

        return results


def execute(
    evaluation_tasks: List[EvaluationTask[T]],
    workers: int,
    verbose: int,
    desc: str = "Evaluation",
) -> List[T]:
    if workers == 1:
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

    with futures.ThreadPoolExecutor(max_workers=workers) as pool:
        test_result_futures = [
            pool.submit(evaluation_task) for evaluation_task in evaluation_tasks
        ]

        test_results = [
            test_result_future.result()
            for test_result_future in _tqdm(
                futures.as_completed(
                    test_result_futures,
                ),
                disable=(verbose < 1),
                desc=desc,
                total=len(test_result_futures),
            )
        ]

    return test_results
