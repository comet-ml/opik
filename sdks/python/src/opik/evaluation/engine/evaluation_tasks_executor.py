from abc import ABC, abstractmethod
from concurrent import futures
from typing import Any, List, TypeVar, Generic

from ...environment import get_tqdm_for_current_environment
from .types import EvaluationTask

_tqdm = get_tqdm_for_current_environment()

T = TypeVar("T")


class BaseStreamingExecutor(ABC, Generic[T]):
    """
    Base class for executors that accept tasks incrementally and process them as they arrive.
    Useful for streaming scenarios where tasks are generated on-the-fly.
    """

    def __init__(self, verbose: int, desc: str = "Evaluation"):
        self.verbose = verbose
        self.desc = desc
        self.task_count = 0

    @abstractmethod
    def __enter__(self) -> "BaseStreamingExecutor[T]":
        """Enter the context manager."""
        pass

    @abstractmethod
    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        """Exit the context manager."""
        pass

    @abstractmethod
    def submit(self, task: EvaluationTask[T]) -> None:
        """Submit a task for execution."""
        pass

    @abstractmethod
    def get_results(self) -> List[T]:
        """Collect all results from submitted tasks."""
        pass


class SingleWorkerStreamingExecutor(BaseStreamingExecutor[T]):
    """
    Executor for single-worker (synchronous) execution.
    Tasks are stored and executed sequentially when results are collected.
    """

    def __init__(self, verbose: int, desc: str = "Evaluation"):
        super().__init__(verbose, desc)
        self.submitted_tasks: List[EvaluationTask[T]] = []

    def __enter__(self) -> "SingleWorkerStreamingExecutor[T]":
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        pass

    def submit(self, task: EvaluationTask[T]) -> None:
        """Submit a task for execution."""
        self.task_count += 1
        self.submitted_tasks.append(task)

    def get_results(self) -> List[T]:
        """Execute tasks synchronously with progress bar and collect results."""
        results: List[T] = []
        for task in _tqdm(
            self.submitted_tasks,
            disable=(self.verbose < 1),
            desc=self.desc,
            total=self.task_count,
        ):
            results.append(task())
        return results


class MultiWorkerStreamingExecutor(BaseStreamingExecutor[T]):
    """
    Executor for multi-worker (parallel) execution.
    Tasks are submitted to a thread pool and executed concurrently.
    """

    def __init__(self, workers: int, verbose: int, desc: str = "Evaluation"):
        super().__init__(verbose, desc)
        self.workers = workers
        self.pool: futures.ThreadPoolExecutor
        self.submitted_futures: List[futures.Future[T]] = []

    def __enter__(self) -> "MultiWorkerStreamingExecutor[T]":
        self.pool = futures.ThreadPoolExecutor(max_workers=self.workers)
        self.pool.__enter__()
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        self.pool.__exit__(exc_type, exc_val, exc_tb)

    def submit(self, task: EvaluationTask[T]) -> None:
        """Submit a task to the thread pool for execution."""
        self.task_count += 1
        future = self.pool.submit(task)
        self.submitted_futures.append(future)

    def get_results(self) -> List[T]:
        """Collect results from futures as they complete with progress bar."""
        results: List[T] = []
        for future in _tqdm(
            futures.as_completed(self.submitted_futures),
            disable=(self.verbose < 1),
            desc=self.desc,
            total=self.task_count,
        ):
            results.append(future.result())
        return results


def StreamingExecutor(
    workers: int, verbose: int, desc: str = "Evaluation"
) -> BaseStreamingExecutor[T]:
    """
    Factory function that returns the appropriate streaming executor based on worker count.

    Args:
        workers: Number of worker threads. If 1, returns SingleWorkerStreamingExecutor.
                 If > 1, returns MultiWorkerStreamingExecutor.
        verbose: Verbosity level for progress bars.
        desc: Description for progress bars.

    Returns:
        An instance of the appropriate streaming executor.
    """
    if workers == 1:
        return SingleWorkerStreamingExecutor[T](verbose=verbose, desc=desc)
    else:
        return MultiWorkerStreamingExecutor[T](
            workers=workers, verbose=verbose, desc=desc
        )


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
