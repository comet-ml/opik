from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
from opik.evaluation.models import base_model


class AgentToolCorrectnessJudge(GEvalPreset):
    """Evaluates whether an agent used tools correctly."""

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


class AgentTaskCompletionJudge(GEvalPreset):
    """Scores whether an agent completed the assigned task."""

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
