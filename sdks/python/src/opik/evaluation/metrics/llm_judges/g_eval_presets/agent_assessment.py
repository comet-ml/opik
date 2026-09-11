from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval import metric as g_eval_metric
from opik.evaluation.models import base_model


class AgentToolCorrectnessJudge(g_eval_metric.GEvalPreset):
    """
    Judge whether an agent invoked and interpreted tools correctly.

    Args:
        model: Optional model identifier or pre-configured ``OpikBaseModel``.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature supplied to the underlying model.

    Example:
        >>> from opik.evaluation.metrics import AgentToolCorrectnessJudge
        >>> judge = AgentToolCorrectnessJudge(model="gpt-4")
        >>> transcript = "Agent called search_tool and used the answer correctly."
        >>> result = judge.score(output=transcript)  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.8
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="agent_tool_correctness",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="agent_tool_correctness_judge",
        )


class AgentTaskCompletionJudge(g_eval_metric.GEvalPreset):
    """
    Evaluate whether an agent successfully completed the original task.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature for the underlying model.

    Example:
        >>> from opik.evaluation.metrics import AgentTaskCompletionJudge
        >>> judge = AgentTaskCompletionJudge(model="gpt-4")
        >>> result = judge.score(output="Agent delivered the requested summary.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.9
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="agent_task_completion",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="agent_task_completion_judge",
        )
