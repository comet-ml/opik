from concurrent import futures
from typing import List

from ...environment import get_tqdm_for_current_environment
from .. import test_result
from .types import EvaluationTask

_tqdm = get_tqdm_for_current_environment()


def execute(
    evaluation_tasks: List[EvaluationTask], workers: int, verbose: int
) -> List[test_result.TestResult]:
    if workers == 1:
        test_results = [
            evaluation_task()
            for evaluation_task in _tqdm(
                evaluation_tasks,
                disable=(verbose < 1),
                desc="Evaluation",
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
                desc="Evaluation",
                total=len(test_result_futures),
            )
        ]

    return test_results
