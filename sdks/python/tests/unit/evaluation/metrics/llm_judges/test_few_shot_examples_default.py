import asyncio
from unittest import mock

import pytest

from opik.evaluation.models import base_model
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


class _Captured(Exception):
    """Stops scoring once the prompt has been sent, so no parser is involved."""


def _capturing_model():
    sent = []

    def record(messages, **kwargs):
        sent.append(str(messages))
        raise _Captured()

    model = mock.Mock(spec=base_model.OpikBaseModel)
    model.generate_chat_completion.side_effect = record
    model.agenerate_chat_completion.side_effect = record
    return model, sent


SCORE_KWARGS = {
    "input": "What is the capital of Italy?",
    "output": "Rome.",
    "expected_output": "Rome.",
    "context": ["Rome is the capital of Italy."],
}


@pytest.mark.parametrize("metric_class, template", JUDGES)
@pytest.mark.parametrize(
    "few_shot_examples, expect_examples", [(None, True), ([], False)]
)
def test_prompt_sent_to_model_follows_few_shot_examples(
    metric_class, template, few_shot_examples, expect_examples
):
    model, sent = _capturing_model()
    metric = metric_class(model=model, few_shot_examples=few_shot_examples, track=False)
    example_text = template.FEW_SHOT_EXAMPLES[0]["input"]

    with pytest.raises(_Captured):
        metric.score(**SCORE_KWARGS)
    with pytest.raises(_Captured):
        asyncio.run(metric.ascore(**SCORE_KWARGS))

    assert len(sent) == 2
    for prompt in sent:
        assert (example_text in prompt) is expect_examples
        # the user's own inputs always reach the model
        assert SCORE_KWARGS["input"] in prompt
