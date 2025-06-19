from concurrent import futures
from typing import List

from . import evaluation_result
from . import _types
from ...environment import get_tqdm_for_current_environment


_tqdm = get_tqdm_for_current_environment()


def execute(
    evaluation_tasks: List[_types.EvaluationTask],
    workers: int,
    verbose: int,
) -> evaluation_result.ThreadsEvaluationResult:
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

        return _extract_results(test_results)

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

    return _extract_results(test_results)


def _extract_results(
    test_results: List[_types.ThreadTestResult],
) -> evaluation_result.ThreadsEvaluationResult:
    return evaluation_result.ThreadsEvaluationResult(
        results={thread_id: test_result for thread_id, test_result in test_results}
    )
