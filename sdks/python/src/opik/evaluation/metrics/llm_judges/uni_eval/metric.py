from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
from opik.evaluation.models import base_model


class UniEvalSummarizationConsistency(GEvalPreset):
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
            name="uni_eval_summarization_consistency_metric",
        )


class UniEvalSummarizationCoherence(GEvalPreset):
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
            name="uni_eval_summarization_coherence_metric",
        )


class UniEvalDialogueHelpfulness(GEvalPreset):
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
            name="uni_eval_dialogue_helpfulness_metric",
        )


class UniEvalQARelevance(GEvalPreset):
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
            name="uni_eval_qa_relevance_metric",
        )
