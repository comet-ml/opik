from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
from opik.evaluation.models import base_model


class SummarizationConsistencyJudge(GEvalPreset):
    """Scores how faithful a summary is to its source content."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="summarization_consistency",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="summarization_consistency_judge",
        )


class SummarizationCoherenceJudge(GEvalPreset):
    """Evaluates coherence and structure of generated summaries."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="summarization_coherence",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="summarization_coherence_judge",
        )


class DialogueHelpfulnessJudge(GEvalPreset):
    """Judges how helpful an assistant reply is within a dialogue."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="dialogue_helpfulness",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="dialogue_helpfulness_judge",
        )


class QARelevanceJudge(GEvalPreset):
    """Checks whether an answer directly addresses the user question."""

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
    ) -> None:
        super().__init__(
            preset="qa_relevance",
            model=model,
            track=track,
            project_name=project_name,
            temperature=temperature,
            name="qa_relevance_judge",
        )
