from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval import metric as g_eval_metric
from opik.evaluation.models import base_model


class PromptUncertaintyJudge(g_eval_metric.GEvalPreset):
    """
    Rate how ambiguous or underspecified a prompt feels to the model.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the underlying model.

    Example:
        >>> from opik.evaluation.metrics import PromptUncertaintyJudge
        >>> judge = PromptUncertaintyJudge(model="gpt-4")
        >>> result = judge.score(output="Do the right thing in the best way possible.")  # doctest: +SKIP
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
            preset="prompt_uncertainty",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="prompt_uncertainty_judge",
        )
