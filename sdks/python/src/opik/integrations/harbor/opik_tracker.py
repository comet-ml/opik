"""
Opik tracking integration for Harbor benchmark evaluation framework.

This module provides the `track_harbor` function to add Opik tracing to Harbor Jobs,
enabling real-time streaming of trial results to Opik for visualization and analysis.

Example:
    >>> from opik.integrations.harbor import track_harbor
    >>> from harbor.job import Job
    >>> import os
    >>>
    >>> os.environ["OPIK_PROJECT_NAME"] = "swebench-evaluation"
    >>>
    >>> job = Job(config)
    >>> tracked_job = track_harbor(job)
    >>> result = await tracked_job.run()

Or enable tracking globally (for CLI usage):
    >>> from opik.integrations.harbor import track_harbor
    >>> track_harbor()
    >>> # Now run Harbor code - it will be traced
"""

import functools
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple
from typing_extensions import override

from harbor.job import Job
from harbor.models.trajectories.step import Step
from harbor.models.trial.result import TrialResult
from harbor.models.verifier.result import VerifierResult
from harbor.trial.trial import Trial
from harbor.verifier.verifier import Verifier

from opik import datetime_helpers, id_helpers, opik_context, track
from opik.api_objects import opik_client, span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import FeedbackScoreDict, SpanType

from . import experiment_service

LOGGER = logging.getLogger(__name__)


class HarborTrialRunDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Decorator for tracking Harbor Trial.run method.

    Sets the trace name based on trial configuration before the span/trace
    is sent to the backend.
    """

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        """Extract trial config and set trace name, input, metadata, and tags."""
        # Extract Trial instance from args (Trial.run is an instance method)
        if not args:
            # Fallback if no args (shouldn't happen for instance methods)
            name = (
                track_options.name if track_options.name is not None else func.__name__
            )
            return arguments_helpers.StartSpanParameters(
                name=name,
                input=None,
                type=track_options.type,
                tags=track_options.tags,
                metadata=track_options.metadata,
                project_name=track_options.project_name,
            )

        trial: Trial = args[0]
        config = trial.config

        # Build trace name from config
        trace_name = f"{config.agent.name}/{config.trial_name}"

        # Build input dict
        input_dict: Dict[str, Any] = {
            "trial_name": config.trial_name,
            "task": {
                "name": config.task.name
                if hasattr(config.task, "name")
                else str(config.task.path),
                "source": getattr(config.task, "source", None),
            },
            "agent": {
                "name": config.agent.name,
                "model": getattr(config.agent, "model_name", None),
            },
        }

        # Build metadata
        metadata = (
            track_options.metadata.copy() if track_options.metadata is not None else {}
        )
        metadata["created_from"] = "harbor"

        # Build tags
        tags = track_options.tags if track_options.tags is not None else []
        tags = list(tags)  # Make a copy to avoid mutating the original
        if "harbor" not in tags:
            tags.append("harbor")
        if config.agent.name not in tags:
            tags.append(config.agent.name)

        return arguments_helpers.StartSpanParameters(
            name=trace_name,
            input=input_dict,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """Process output - minimal implementation since output is handled in _wrap_trial_run."""
        # Output is handled separately in _wrap_trial_run via opik_context.update_current_trace
        # So we don't need to process it here
        return arguments_helpers.EndSpanParameters(output=None)

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        """No stream handling needed for Trial.run."""
        return None


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


def _source_to_span_type(source: str) -> SpanType:
    """Convert ATIF step source to Opik span type."""
    if source == "agent":
        return "llm"
    return "general"


def _patch_step_class() -> None:
    """Patch the Harbor Step class to create Opik spans on instantiation."""
    # Check if already patched
    if hasattr(_patch_step_class, "_patched"):
        return

    original_init = Step.__init__

    @functools.wraps(original_init)
    def patched_init(self: Step, *args: Any, **kwargs: Any) -> None:
        original_init(self, *args, **kwargs)

        trace_data = opik_context.get_current_trace_data()
        if trace_data is None:
            return

        parent_span = opik_context.get_current_span_data()
        parent_span_id = parent_span.id if parent_span else None

        try:
            client = opik_client.get_client_cached()

            input_dict: Dict[str, Any] = {}
            if self.message:
                input_dict["message"] = self.message
            if self.tool_calls:
                input_dict["tool_calls"] = [
                    {
                        "tool_call_id": tc.tool_call_id,
                        "function_name": tc.function_name,
                        "arguments": tc.arguments,
                    }
                    for tc in self.tool_calls
                ]

            output_dict: Optional[Dict[str, Any]] = None
            if self.observation and self.observation.results:
                output_dict = {
                    "results": [
                        {"content": r.content} for r in self.observation.results
                    ]
                }

            metadata: Dict[str, Any] = {
                "source": self.source,
                "created_from": "harbor",
            }
            if self.reasoning_content:
                metadata["reasoning"] = self.reasoning_content

            usage: Optional[Dict[str, Any]] = None
            total_cost: Optional[float] = None
            if self.metrics:
                usage = {}
                if self.metrics.prompt_tokens is not None:
                    usage["prompt_tokens"] = self.metrics.prompt_tokens
                if self.metrics.completion_tokens is not None:
                    usage["completion_tokens"] = self.metrics.completion_tokens
                if self.metrics.prompt_tokens and self.metrics.completion_tokens:
                    usage["total_tokens"] = (
                        self.metrics.prompt_tokens + self.metrics.completion_tokens
                    )
                if not usage:
                    usage = None
                total_cost = getattr(self.metrics, "cost_usd", None)

            client.span(
                id=id_helpers.generate_id(),
                trace_id=trace_data.id,
                parent_span_id=parent_span_id,
                name=f"step_{self.step_id}",
                type=_source_to_span_type(self.source),
                start_time=datetime_helpers.parse_iso_timestamp(self.timestamp),
                input=input_dict if input_dict else None,
                output=output_dict,
                metadata=metadata,
                usage=usage,
                total_cost=total_cost,
                model=self.model_name if self.source == "agent" else None,
                tags=["harbor", self.source],
            )

        except Exception as e:
            LOGGER.debug("Failed to create span for step: %s", e)

    Step.__init__ = patched_init  # type: ignore
    setattr(_patch_step_class, "_patched", True)


def _enable_harbor_tracking(project_name: Optional[str] = None) -> None:
    """Internal: Enable Opik tracking for Harbor by patching classes.

    This patches Harbor's Trial and Verifier classes to add tracing.

    Args:
        project_name: Opik project name. If None, uses OPIK_PROJECT_NAME env var.
    """
    # Patch Trial methods (only if not already patched)
    if not hasattr(Trial.run, "opik_tracked"):
        Trial.run = _wrap_trial_run(Trial.run, project_name)

    if not hasattr(Trial._setup_environment, "opik_tracked"):
        Trial._setup_environment = _wrap_setup_environment(
            Trial._setup_environment, project_name
        )

    if not hasattr(Trial._setup_agent, "opik_tracked"):
        Trial._setup_agent = _wrap_setup_agent(Trial._setup_agent, project_name)

    if not hasattr(Trial._execute_agent, "opik_tracked"):
        Trial._execute_agent = _wrap_execute_agent(Trial._execute_agent, project_name)

    if not hasattr(Trial._run_verification, "opik_tracked"):
        Trial._run_verification = _wrap_run_verification(
            Trial._run_verification, project_name
        )

    # Patch Verifier (only if not already patched)
    if not hasattr(Verifier.verify, "opik_tracked"):
        Verifier.verify = _wrap_verify(Verifier.verify, project_name)

    # Patch Step class for real-time step tracking
    _patch_step_class()

    LOGGER.info("Opik tracking enabled for Harbor")


def track_harbor(
    job: Optional["Job"] = None,
    project_name: Optional[str] = None,
) -> Optional["Job"]:
    """Enable Opik tracking for Harbor.

    Can be called two ways:
    - track_harbor() - enables global tracking (for CLI usage)
    - track_harbor(job) - wraps a job and enables tracking (for SDK usage)

    Args:
        job: Optional Harbor Job instance. If provided, returns the same job.
        project_name: Opik project name. If None, uses OPIK_PROJECT_NAME env var.

    Returns:
        The job instance if provided, None otherwise.

    Example:
        >>> from opik.integrations.harbor import track_harbor
        >>> job = Job(config)
        >>> tracked_job = track_harbor(job)
        >>> result = await tracked_job.run()
    """
    _enable_harbor_tracking(project_name=project_name)
    return job


def _wrap_trial_run(original: Callable, project_name: Optional[str]) -> Callable:
    """Wrap Trial.run with tracing, feedback scores, and experiment linking."""

    decorator = HarborTrialRunDecorator()

    @decorator.track(
        tags=["harbor"],
        project_name=project_name,
        capture_output=False,
    )
    @functools.wraps(original)
    async def wrapped(self: Trial) -> TrialResult:
        config = self.config

        # Lazily setup experiment service if not already done
        # This ensures experiment tracking works for both SDK and CLI modes
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

        result: TrialResult = await original(self)

        # Update trace with output and feedback scores
        output_dict: Dict[str, Any] = {
            "trial_name": result.trial_name,
            "task_name": result.task_name,
        }
        if result.verifier_result and result.verifier_result.rewards:
            output_dict["rewards"] = result.verifier_result.rewards

        feedback_scores = None
        if result.verifier_result and result.verifier_result.rewards:
            # Get error message if available
            error_msg = getattr(result.verifier_result, "error", None) or getattr(
                result, "error", None
            )
            feedback_scores = _rewards_to_feedback_scores(
                result.verifier_result.rewards, error=error_msg
            )

        opik_context.update_current_trace(
            output=output_dict,
            feedback_scores=feedback_scores,
        )

        # Link to experiment
        trace_data = opik_context.get_current_trace_data()
        if trace_data is not None:
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

        return result

    return wrapped


def _wrap_setup_environment(
    original: Callable, project_name: Optional[str]
) -> Callable:
    """Wrap Trial._setup_environment with tracing."""

    @track(name="setup_environment", tags=["harbor"], project_name=project_name)
    @functools.wraps(original)
    async def wrapped(self: Trial) -> None:
        opik_context.update_current_span(
            input={"phase": "environment_setup"},
            metadata={"created_from": "harbor"},
        )
        await original(self)
        opik_context.update_current_span(output={"status": "completed"})

    return wrapped


def _wrap_setup_agent(original: Callable, project_name: Optional[str]) -> Callable:
    """Wrap Trial._setup_agent with tracing."""

    @track(name="setup_agent", tags=["harbor"], project_name=project_name)
    @functools.wraps(original)
    async def wrapped(self: Trial) -> None:
        opik_context.update_current_span(
            input={"phase": "agent_setup"},
            metadata={"created_from": "harbor"},
        )
        await original(self)
        opik_context.update_current_span(output={"status": "completed"})

    return wrapped


def _wrap_execute_agent(original: Callable, project_name: Optional[str]) -> Callable:
    """Wrap Trial._execute_agent with tracing."""

    @track(name="execute_agent", tags=["harbor"], project_name=project_name)
    @functools.wraps(original)
    async def wrapped(self: Trial) -> None:
        input_dict = {}
        if hasattr(self, "_task") and self._task:
            input_dict["instruction"] = self._task.instruction
        opik_context.update_current_span(
            input=input_dict,
            metadata={"created_from": "harbor"},
        )
        await original(self)
        opik_context.update_current_span(output={"status": "completed"})

    return wrapped


def _wrap_run_verification(original: Callable, project_name: Optional[str]) -> Callable:
    """Wrap Trial._run_verification with tracing."""

    @track(name="run_verification", tags=["harbor"], project_name=project_name)
    @functools.wraps(original)
    async def wrapped(self: Trial) -> None:
        opik_context.update_current_span(
            input={"phase": "verification"},
            metadata={"created_from": "harbor"},
        )
        await original(self)
        opik_context.update_current_span(output={"status": "completed"})

    return wrapped


def _wrap_verify(original: Callable, project_name: Optional[str]) -> Callable:
    """Wrap Verifier.verify with tracing."""

    @track(name="verify", tags=["harbor"], project_name=project_name)
    @functools.wraps(original)
    async def wrapped(self: Verifier) -> VerifierResult:
        opik_context.update_current_span(
            input={"phase": "verify"},
            metadata={"created_from": "harbor"},
        )
        result: VerifierResult = await original(self)

        output_dict: Dict[str, Any] = {}
        if result.rewards:
            output_dict["rewards"] = result.rewards
        opik_context.update_current_span(
            output=output_dict if output_dict else {"status": "completed"}
        )

        return result

    return wrapped


def reset_harbor_tracking() -> None:
    """Reset Harbor tracking state for testing purposes.

    Resets the experiment service. Method patches remain active
    (they use `opik_tracked` to prevent double-patching).
    """
    experiment_service.reset()
