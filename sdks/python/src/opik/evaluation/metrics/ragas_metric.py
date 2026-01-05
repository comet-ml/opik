from opik.evaluation.metrics import base_metric, score_result
import opik.exceptions as exceptions

from typing import Dict, Any, Optional, TYPE_CHECKING
import opik.opik_context as opik_context

if TYPE_CHECKING:
    from ragas import metrics as ragas_metrics
    from ragas import dataset_schema as ragas_dataset_schema
    from opik.integrations.langchain import OpikTracer


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
        sample = self._create_ragas_single_turn_sample(kwargs)

        callbacks = [_get_opik_tracer_instance()] if self.track else []

        score = await self.ragas_metric.single_turn_ascore(sample, callbacks=callbacks)
        return score_result.ScoreResult(value=score, name=self.name)

    def score(self, **kwargs: Any) -> score_result.ScoreResult:
        sample = self._create_ragas_single_turn_sample(kwargs)

        callbacks = [_get_opik_tracer_instance()] if self.track else []

        score = self.ragas_metric.single_turn_score(sample, callbacks=callbacks)
        return score_result.ScoreResult(value=score, name=self.name)


def _get_opik_tracer_instance() -> "OpikTracer":
    from opik.integrations.langchain import OpikTracer

    current_span_data = opik_context.get_current_span_data()
    current_trace_data = opik_context.get_current_trace_data()
    project_name = None

    if current_span_data is not None:
        project_name = (
            current_trace_data.project_name
            if current_trace_data is not None
            else current_span_data.project_name
        )

    # OPIK-3505: Why opik_context_read_only_mode=True?
    #
    # Problem: Ragas runs metrics concurrently under the hood with a manual management
    # of the event loop. It was discovered that these metrics share the same context and so
    # ContextVar used in Opik context storage can't be modified safely by them because concurrent
    # operations share the same span stack.
    #
    # Solution: Disable context modification (opik_context_read_only_mode=True).
    # OpikTracer will still create spans/traces and track parent-child relationships
    # using LangChain's Run IDs, but won't modify the shared ContextVar storage.
    #
    # Trade-off: @track-decorated functions called within Ragas won't be attached
    # to the Ragas spans. This is acceptable since Ragas metrics are self-contained
    # and don't typically call user-defined tracked functions.
    opik_tracer = OpikTracer(
        opik_context_read_only_mode=True,
        project_name=project_name,
    )
    return opik_tracer
