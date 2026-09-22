import pytest

from opik.evaluation.metrics.llm_judges.context_precision import (
    metric as context_precision,
    template as context_precision_template,
)
from opik.evaluation.metrics.llm_judges.context_recall import (
    metric as context_recall,
    template as context_recall_template,
)
from opik.evaluation.metrics.llm_judges.factuality import (
    metric as factuality,
    template as factuality_template,
)

JUDGES = [
    (context_precision.ContextPrecision, context_precision_template),
    (context_recall.ContextRecall, context_recall_template),
    (factuality.Factuality, factuality_template),
]


@pytest.mark.parametrize("metric_class, template", JUDGES)
def test_none_few_shot_examples_uses_the_defaults(metric_class, template):
    metric = metric_class(track=False)
    assert metric.few_shot_examples == template.FEW_SHOT_EXAMPLES


@pytest.mark.parametrize("metric_class, template", JUDGES)
def test_empty_few_shot_examples_disables_them(metric_class, template):
    assert template.FEW_SHOT_EXAMPLES, "defaults must be non-empty for this test"
    metric = metric_class(few_shot_examples=[], track=False)
    assert metric.few_shot_examples == []
