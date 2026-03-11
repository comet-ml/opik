from typing import List

from opik.evaluation.metrics import base_metric

from . import metric as llm_judge_metric


def merge_llm_judges(
    metrics: List[base_metric.BaseMetric],
) -> List[base_metric.BaseMetric]:
    """Merge multiple LLMJudge instances into one to reduce LLM API calls.

    Replaces the first LLMJudge with the merged instance and removes the rest,
    preserving the relative order of all other metrics. Returns the original
    list unchanged if there are 0-1 judges or if settings differ.
    """
    judges: List[llm_judge_metric.LLMJudge] = [
        m for m in metrics if isinstance(m, llm_judge_metric.LLMJudge)
    ]

    if len(judges) <= 1:
        return metrics

    merged = llm_judge_metric.LLMJudge.merged(judges)
    if merged is None:
        return metrics

    judge_set = set(id(j) for j in judges)
    result: List[base_metric.BaseMetric] = []
    first_replaced = False
    for m in metrics:
        if id(m) in judge_set:
            if not first_replaced:
                result.append(merged)
                first_replaced = True
        else:
            result.append(m)

    return result
