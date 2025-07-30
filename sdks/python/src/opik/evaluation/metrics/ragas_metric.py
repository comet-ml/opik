import asyncio

from opik.evaluation.metrics import base_metric, score_result
import opik.exceptions as exceptions

from typing import Dict, Any, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from ragas import metrics as ragas_metrics
    from ragas import dataset_schema as ragas_dataset_schema


def get_or_create_asyncio_loop() -> asyncio.AbstractEventLoop:
    try:
        return asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.new_event_loop()


class RagasMetricWrapper(base_metric.BaseMetric):
    def __init__(
        self,
        ragas_metric: "ragas_metrics.SingleTurnMetric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        from ragas import metrics as ragas_metrics

        # Provide a better error message if user try to pass a Ragas metric that is not a single turn metric
        if not isinstance(ragas_metric, ragas_metrics.SingleTurnMetric):
            raise ValueError(
                f"ragas_metric {type(ragas_metric)} is not a SingleTurnMetric Ragas Metric"
            )
        super().__init__(name=ragas_metric.name, track=track, project_name=project_name)
        self.ragas_metric = ragas_metric
        self._required_fields = ragas_metric.required_columns[
            ragas_metrics.MetricType.SINGLE_TURN.name
        ]

    def _create_ragas_single_turn_sample(
        self, input_dict: Dict[str, Any]
    ) -> "ragas_dataset_schema.SingleTurnSample":
        from ragas import dataset_schema as ragas_dataset_schema

        # Add basic field name mapping between Opik and Ragas
        if "user_input" not in input_dict and "input" in input_dict:
            input_dict["user_input"] = input_dict["input"]

        if "response" not in input_dict and "output" in input_dict:
            input_dict["response"] = input_dict["output"]

        sample_dict = {}
        missing_arguments = []
        for field in self._required_fields:
            try:
                sample_dict[field] = input_dict[field]
            except KeyError:
                missing_arguments.append(field)

        if len(missing_arguments) > 0:
            raise exceptions.ScoreMethodMissingArguments(
                self.name,
                missing_arguments,
                list(input_dict.keys()),
            )

        sample = ragas_dataset_schema.SingleTurnSample(**sample_dict)
        return sample

    async def ascore(self, **kwargs: Any) -> score_result.ScoreResult:
        return await self._ascore(kwargs)

    async def _ascore(self, input_dict: Dict[str, Any]) -> score_result.ScoreResult:
        """Separate internal function to avoid creating two spans. Both score and ascore are track-decorated and if score calls ascore, two spans would be created."""
        sample = self._create_ragas_single_turn_sample(input_dict)

        if self.track:
            from opik.integrations.langchain import OpikTracer

            opik_tracer = OpikTracer()
            callbacks = [opik_tracer]
        else:
            callbacks = []

        # TODO: Add LLM callback when the metric is using an LLM
        score = await self.ragas_metric.single_turn_ascore(sample, callbacks=callbacks)
        return score_result.ScoreResult(value=score, name=self.name)

    def score(self, **kwargs: Any) -> score_result.ScoreResult:
        # Run the async function using the current event loop
        loop = get_or_create_asyncio_loop()

        result = loop.run_until_complete(self._ascore(kwargs))

        return result
