from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval import metric as g_eval_metric
from opik.evaluation.models import base_model


class DemographicBiasJudge(g_eval_metric.GEvalPreset):
    """
    Score demographic stereotyping or bias in a response.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the model.

    Example:
        >>> from opik.evaluation.metrics import DemographicBiasJudge
        >>> judge = DemographicBiasJudge(model="gpt-4")
        >>> result = judge.score(output="People from X group are always late.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.95
    """

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


class PoliticalBiasJudge(g_eval_metric.GEvalPreset):
    """
    Detect partisan or ideological bias in a response.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the model.

    Example:
        >>> from opik.evaluation.metrics import PoliticalBiasJudge
        >>> judge = PoliticalBiasJudge(model="gpt-4")
        >>> result = judge.score(output="Vote for candidate X because Y is corrupt")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.87
    """

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


class GenderBiasJudge(g_eval_metric.GEvalPreset):
    """
    Detect gender stereotyping or exclusion in generated text.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the model.

    Example:
        >>> from opik.evaluation.metrics import GenderBiasJudge
        >>> judge = GenderBiasJudge(model="gpt-4")
        >>> result = judge.score(output="Women are naturally worse at math.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.93
    """

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


class ReligiousBiasJudge(g_eval_metric.GEvalPreset):
    """
    Evaluate responses for religious bias or disrespectful language.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the model.

    Example:
        >>> from opik.evaluation.metrics import ReligiousBiasJudge
        >>> judge = ReligiousBiasJudge(model="gpt-4")
        >>> result = judge.score(output="Believers of X are all foolish.")  # doctest: +SKIP
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
            preset="bias_religion",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="religious_bias_judge",
        )


class RegionalBiasJudge(g_eval_metric.GEvalPreset):
    """
    Assess geographic or cultural bias in responses.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the model.

    Example:
        >>> from opik.evaluation.metrics import RegionalBiasJudge
        >>> judge = RegionalBiasJudge(model="gpt-4")
        >>> result = judge.score(output="People from region Z are lazy.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.88
    """

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
