from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
from opik.evaluation.models import base_model


class PromptPerplexityJudge(GEvalPreset):
    """Rates how difficult a prompt is for an LLM to interpret."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="prompt_perplexity",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="prompt_perplexity_judge",
        )


class PromptUncertaintyJudge(GEvalPreset):
    """Rates the amount of ambiguity/uncertainty in a prompt."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="prompt_uncertainty",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="prompt_uncertainty_judge",
        )
