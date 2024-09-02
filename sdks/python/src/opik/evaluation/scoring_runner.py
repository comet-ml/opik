from typing import List
from concurrent import futures

from .metrics import base_metric, score_result

from . import test_result, test_case

import tqdm


def _process_test_case(
    test_case_: test_case.TestCase, scoring_metrics: List[base_metric.BaseMetric]
) -> test_result.TestResult:
    score_results = []
    for metric in scoring_metrics:
        try:
            result = metric.score(
                **test_case_.task_output.model_dump(exclude_none=False)
            )
            if isinstance(result, list):
                score_results += result
            else:
                score_results.append(result)
        except Exception as e:
            # This can be problematic if the metric returns a list of strings as we will not know the name of the metrics that have failed
            score_results.append(
                score_result.ScoreResult(
                    name=metric.name, value=0.0, reason=str(e), scoring_failed=True
                )
            )

    test_result_ = test_result.TestResult(
        test_case=test_case_, score_results=score_results
    )

    return test_result_


def run(
    test_cases: List[test_case.TestCase],
    scoring_metrics: List[base_metric.BaseMetric],
    workers: int,
    verbose: int,
) -> List[test_result.TestResult]:
    test_results: List[test_result.TestResult]

    if workers == 1:
        test_results = [
            _process_test_case(test_case_, scoring_metrics)
            for test_case_ in tqdm.tqdm(
                test_cases,
                disable=(verbose < 1),
                desc="Scoring outputs",
                total=len(test_cases),
            )
        ]

        return test_results

    with futures.ThreadPoolExecutor(max_workers=workers) as pool:
        test_result_futures = [
            pool.submit(_process_test_case, test_case_, scoring_metrics)
            for test_case_ in test_cases
        ]

        test_results = [
            test_result_future.result()
            for test_result_future in tqdm.tqdm(
                futures.as_completed(test_result_futures),
                disable=(verbose < 1),
                desc="Scoring outputs",
                total=len(test_result_futures),
            )
        ]

    return test_results
