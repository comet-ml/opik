import asyncio

from opik.evaluation.metrics import base_metric, score_result
from ragas.dataset_schema import SingleTurnSample
from ragas.metrics.base import Metric as RagasBaseMetric
from ragas.metrics import MetricType
from opik.exceptions import ScoreMethodMissingArguments

from typing import Dict, Any


def get_or_create_asyncio_loop() -> asyncio.AbstractEventLoop:
    try:
        return asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.new_event_loop()


class RagasMetricWrapper(base_metric.BaseMetric):
    def __init__(self, ragas_metric: RagasBaseMetric):
        self.name = ragas_metric.name
        self.ragas_metric = ragas_metric
        print("RAGAS METRIC", ragas_metric.required_columns)
        self._required_fields = ragas_metric.required_columns[
            MetricType.SINGLE_TURN.name
        ]
        print("SELF._required_fields", self._required_fields)

    def _create_ragas_single_turn_sample(
        self, input_dict: Dict[str, Any]
    ) -> SingleTurnSample:
        # Add basic field name mapping between Opik and Ragas
        if "user_input" not in input_dict:
            input_dict["user_input"] = input_dict["input"]

        if "response" not in input_dict:
            input_dict["response"] = input_dict["output"]

        sample_dict = {}
        missing_arguments = []
        for field in self._required_fields:
            try:
                sample_dict[field] = input_dict[field]
            except KeyError:
                missing_arguments.append(field)

        if len(missing_arguments) > 0:
            # TODO: Use raise_if_score_arguments_are_missing
            raise ScoreMethodMissingArguments(
                f"Metric {self.name} requires fields: {missing_arguments}"
            )

        sample = SingleTurnSample(**sample_dict)
        return sample

    async def ascore(self, **kwargs: Any) -> score_result.ScoreResult:
        sample = self._create_ragas_single_turn_sample(kwargs)

        # TODO: Add LLM callback when the metric is using an LLM
        score = await self.ragas_metric.single_turn_ascore(sample)
        return score_result.ScoreResult(value=score, name=self.name)

    def score(self, **kwargs: Any) -> score_result.ScoreResult:
        # Run the async function using the current event loop
        loop = get_or_create_asyncio_loop()

        result = loop.run_until_complete(self.ascore(**kwargs))

        return result
