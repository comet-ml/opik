"""Harbor trial run decorator for Opik tracking.

This decorator extends BaseTrackDecorator to properly set trace names
for Harbor trial runs before the trace is sent to the backend.
"""

import logging
from typing import Any, Callable, Dict, List, Optional, Tuple, cast
from typing_extensions import override

from harbor.models.trial.result import TrialResult
from harbor.trial.trial import Trial

from opik import opik_context
from opik.decorator import arguments_helpers, base_track_decorator
from opik.api_objects import span
from opik.types import FeedbackScoreDict

from . import experiment_service

LOGGER = logging.getLogger(__name__)


def _rewards_to_feedback_scores(
    rewards: Optional[Dict[str, Any]],
    error: Optional[str] = None,
) -> List[FeedbackScoreDict]:
    """Convert Harbor verifier rewards to Opik feedback scores."""
    if rewards is None:
        return []

    feedback_scores: List[FeedbackScoreDict] = []
    for name, value in rewards.items():
        try:
            float_value = float(value)

            score = FeedbackScoreDict(name=name, value=float_value, reason=error)

            feedback_scores.append(score)
        except (ValueError, TypeError):
            LOGGER.warning(
                "Could not convert reward value to float: %s=%s", name, value
            )

    return feedback_scores


class HarborTrialRunDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Custom decorator for Harbor Trial.run method.

    This decorator computes the trace name dynamically from the Trial instance
    before the trace is sent to the backend, ensuring the correct name appears
    in the UI. It also handles experiment service setup and linking.
    """

    def __init__(self) -> None:
        super().__init__()
        # Store trial instance to access in _end_span_inputs_preprocessor
        self._trial_instance: Optional[Trial] = None

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        """
        Preprocess span inputs to set the trace name dynamically.

        Extracts the Trial instance from args[0] (self) and computes the trace name
        from config.agent.name and config.trial_name before the trace is sent.
        Also sets up the experiment service if needed.
        """
        # Extract the Trial instance (self is the first argument)
        if not args or not isinstance(args[0], Trial):
            # Fallback to default behavior if no args or not a Trial instance
            name = track_options.name if track_options.name is not None else func.__name__
            input_dict = None
            self._trial_instance = None
        else:
            # Type narrowing: args[0] is confirmed to be a Trial instance
            trial_instance = cast(Trial, args[0])
            self._trial_instance = trial_instance
            config = trial_instance.config

            # Compute trace name from config
            agent_name = config.agent.name
            trial_name = config.trial_name
            name = f"{agent_name}/{trial_name}"

            # Build input dictionary
            input_dict: Dict[str, Any] = {
                "trial_name": trial_name,
                "task": {
                    "name": (
                        config.task.name
                        if hasattr(config.task, "name")
                        else str(config.task.path)
                    ),
                    "source": getattr(config.task, "source", None),
                },
                "agent": {
                    "name": agent_name,
                    "model": getattr(config.agent, "model_name", None),
                },
            }

            # Lazily setup experiment service if not already done
            # This ensures experiment tracking works for both SDK and CLI modes
            self._setup_experiment_service(config)

        # Build metadata
        metadata = track_options.metadata if track_options.metadata is not None else {}
        metadata["created_from"] = "harbor"

        # Build tags
        tags = track_options.tags if track_options.tags is not None else []
        if "harbor" not in tags:
            tags = ["harbor"] + tags

        # Add agent name to tags if available
        if self._trial_instance is not None:
            agent_name = self._trial_instance.config.agent.name
            if agent_name not in tags:
                tags.append(agent_name)

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_dict,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    def _setup_experiment_service(self, config: Any) -> None:
        """Setup experiment service if not already done."""
        if experiment_service.get_service() is None:
            try:
                # Use job_id for consistent experiment naming
                experiment_name = (
                    f"harbor-job-{str(config.job_id)[:8]}" if config.job_id else None
                )
                # Build experiment config with agent/model info
                experiment_config: Dict[str, Any] = {
                    "agent_name": config.agent.name,
                }
                model_name = getattr(config.agent, "model_name", None)
                if model_name:
                    experiment_config["model_name"] = model_name

                LOGGER.debug(
                    "Lazily setting up experiment service: experiment_name=%s",
                    experiment_name,
                )
                experiment_service.setup_lazy(
                    experiment_name=experiment_name,
                    experiment_config=experiment_config,
                )
            except Exception as e:
                LOGGER.debug("Failed to lazily setup experiment service: %s", e)

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """
        Preprocess span outputs when the function completes.

        Processes the TrialResult output, extracts feedback scores, and links
        the trial to the experiment.
        """
        assert isinstance(
            output, TrialResult
        ), f"Expected TrialResult, got {type(output)}"

        output_dict: Optional[Dict[str, Any]] = None

        if capture_output:
            output_dict = {
                "trial_name": output.trial_name,
                "task_name": output.task_name,
            }
            # Add rewards if available
            if output.verifier_result and output.verifier_result.rewards:
                output_dict["rewards"] = output.verifier_result.rewards

        # Extract feedback scores from verifier result
        feedback_scores = None
        if output.verifier_result and output.verifier_result.rewards:
            # Get error message if available
            error_msg = getattr(output.verifier_result, "error", None) or getattr(
                output, "error", None
            )
            feedback_scores = _rewards_to_feedback_scores(
                output.verifier_result.rewards, error=error_msg
            )

        # Update trace with output and feedback scores
        if output_dict is not None or feedback_scores is not None:
            opik_context.update_current_trace(
                output=output_dict,
                feedback_scores=feedback_scores,
            )

        # Link to experiment
        if self._trial_instance is not None:
            self._link_trial_to_experiment(output)

        result = arguments_helpers.EndSpanParameters(output=output_dict)

        return result

    def _link_trial_to_experiment(self, result: Any) -> None:
        """Link the trial to the experiment."""
        if self._trial_instance is None:
            return

        config = self._trial_instance.config
        trace_data = opik_context.get_current_trace_data()
        if trace_data is None:
            return

        service = experiment_service.get_service()
        LOGGER.debug(
            "Linking trial to experiment: trial=%s, trace_id=%s, service=%s",
            config.trial_name,
            trace_data.id,
            service,
        )
        if service is not None:
            source = getattr(config.task, "source", None)
            task_name = (
                config.task.name
                if hasattr(config.task, "name")
                else str(config.task.path)
            )
            service.link_trial_to_experiment(
                trial_name=config.trial_name,
                trace_id=trace_data.id,
                source=source,
                task_name=task_name,
            )
        else:
            LOGGER.debug(
                "No experiment service available, skipping experiment linking"
            )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        """
        Handle stream-like objects (not used for Harbor trial runs).

        Returns None to indicate no stream handling is needed.
        """
        return super()._streams_handler(output, capture_output, generations_aggregator)

