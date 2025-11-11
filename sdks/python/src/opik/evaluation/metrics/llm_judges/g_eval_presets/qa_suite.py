from __future__ import annotations

from typing import Optional, Union

from opik.evaluation.metrics.llm_judges.g_eval import metric as g_eval_metric
from opik.evaluation.models import base_model


class SummarizationConsistencyJudge(g_eval_metric.GEvalPreset):
    """
    Score how faithful a summary is to its source content.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the underlying model.

    Example:
        >>> from opik.evaluation.metrics import SummarizationConsistencyJudge
        >>> judge = SummarizationConsistencyJudge(model="gpt-4")
        >>> result = judge.score(output="Summary omits key fact.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.4
    """

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


class SummarizationCoherenceJudge(g_eval_metric.GEvalPreset):
    """
    Evaluate the coherence and structure of generated summaries.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the underlying model.

    Example:
        >>> from opik.evaluation.metrics import SummarizationCoherenceJudge
        >>> judge = SummarizationCoherenceJudge(model="gpt-4")
        >>> result = judge.score(output="Summary jumps between unrelated topics.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.5
    """

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


class DialogueHelpfulnessJudge(g_eval_metric.GEvalPreset):
    """
    Judge how helpful an assistant reply is within a dialogue.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the underlying model.

    Example:
        >>> from opik.evaluation.metrics import DialogueHelpfulnessJudge
        >>> judge = DialogueHelpfulnessJudge(model="gpt-4")
        >>> result = judge.score(output="Assistant politely refuses without help.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.3
    """

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


class QARelevanceJudge(g_eval_metric.GEvalPreset):
    """
    Check whether an answer directly addresses the user question.

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the underlying model.

    Example:
        >>> from opik.evaluation.metrics import QARelevanceJudge
        >>> judge = QARelevanceJudge(model="gpt-4")
        >>> result = judge.score(output="Answer rambles without addressing the ask.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.2
    """

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
