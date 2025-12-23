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
from typing import Any, Callable, Dict, List, Optional

from harbor.job import Job
from harbor.models.trajectories.step import Step
from harbor.models.trial.result import TrialResult
from harbor.models.verifier.result import VerifierResult
from harbor.trial.trial import Trial
from harbor.verifier.verifier import Verifier

from opik import datetime_helpers, id_helpers, opik_context, track
from opik.api_objects import opik_client
from opik.types import SpanType

from . import experiment_service
from .harbor_trial_run_decorator import HarborTrialRunDecorator

LOGGER = logging.getLogger(__name__)


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
        capture_output=True,
    )
    @functools.wraps(original)
    async def wrapped(self: Trial) -> TrialResult:
        result: TrialResult = await original(self)
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
