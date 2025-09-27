from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
from opik.evaluation.models import base_model


class ComplianceRiskJudge(GEvalPreset):
    """Evaluates non-factual or non-compliant statements for regulated sectors."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="compliance_regulated_truthfulness",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="compliance_risk_judge",
        )
