from concurrent import futures
from typing import Any, List, Optional, TypeVar, Generic

from ...environment import get_tqdm_for_current_environment
from .types import EvaluationTask

_tqdm = get_tqdm_for_current_environment()

T = TypeVar("T")


class StreamingExecutor(Generic[T]):
    """
    Executor that accepts and processes evaluation tasks incrementally using a thread pool.

    Tasks can be submitted one at a time and will begin executing immediately, allowing
    for streaming behavior regardless of the number of workers configured.
    """

    def __init__(
        self,
        workers: int,
        verbose: int,
        desc: str = "Evaluation",
        total: Optional[int] = None,
    ):
        self._workers = workers
        self._verbose = verbose
        self._desc = desc
        self._total = total
        self._task_count = 0
        self._pool: futures.ThreadPoolExecutor
        self._submitted_futures: List[futures.Future[T]] = []
        self._progress_bar: Optional[Any] = None

    def __enter__(self) -> "StreamingExecutor[T]":
        self._pool = futures.ThreadPoolExecutor(max_workers=self._workers)
        self._pool.__enter__()
        # Initialize progress bar on enter
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

    def submit(self, task: EvaluationTask[T]) -> None:
        """Submit a task to the thread pool for execution."""
        self._task_count += 1
        future = self._pool.submit(task)
        self._submitted_futures.append(future)

    def get_results(self) -> List[T]:
        """Collect results from futures as they complete with progress bar."""
        results: List[T] = []

        # Update total if it wasn't known initially
        if self._progress_bar is not None and self._total is None:
            self._progress_bar.total = self._task_count

        # Process futures as they complete and update progress bar
        for future in futures.as_completed(self._submitted_futures):
            results.append(future.result())
            if self._progress_bar is not None:
                self._progress_bar.update(1)

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

    with StreamingExecutor[T](
        workers=workers, verbose=verbose, desc=desc, total=len(evaluation_tasks)
    ) as executor:
        for evaluation_task in evaluation_tasks:
            executor.submit(evaluation_task)
        return executor.get_results()
