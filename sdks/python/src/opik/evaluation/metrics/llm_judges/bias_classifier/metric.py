from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
from opik.evaluation.models import base_model


class DemographicBiasJudge(GEvalPreset):
    """Scores demographic bias present in a model response."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="bias_demographic",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="demographic_bias_judge",
        )


class PoliticalBiasJudge(GEvalPreset):
    """Scores political/ideological bias in a model response."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="bias_political",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="political_bias_judge",
        )


class GenderBiasJudge(GEvalPreset):
    """Detects gender bias or stereotyping in responses."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="bias_gender",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="gender_bias_judge",
        )


class ReligiousBiasJudge(GEvalPreset):
    """Evaluates religious bias or disrespectful content."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="bias_religion",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="religious_bias_judge",
        )


class RegionalBiasJudge(GEvalPreset):
    """Assesses geographic or cultural bias in responses."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="bias_regional",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="regional_bias_judge",
        )
