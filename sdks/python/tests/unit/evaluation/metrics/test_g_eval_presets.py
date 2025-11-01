import pytest

from opik.evaluation.metrics.llm_judges.g_eval.metric import GEVAL_PRESETS, GEvalPreset
from opik.evaluation.metrics.llm_judges.g_eval_presets import (
    AgentTaskCompletionJudge,
    AgentToolCorrectnessJudge,
    ComplianceRiskJudge,
    DemographicBiasJudge,
    DialogueHelpfulnessJudge,
    GenderBiasJudge,
    PoliticalBiasJudge,
    PromptUncertaintyJudge,
    QARelevanceJudge,
    RegionalBiasJudge,
    ReligiousBiasJudge,
    SummarizationCoherenceJudge,
    SummarizationConsistencyJudge,
)


def test_g_eval_preset_initialization():
    preset_name = "summarization_consistency"
    metric = GEvalPreset(preset=preset_name, track=False)
    definition = GEVAL_PRESETS[preset_name]

    assert metric.task_introduction == definition.task_introduction
    assert metric.evaluation_criteria == definition.evaluation_criteria


def test_g_eval_preset_unknown():
    with pytest.raises(ValueError):
        GEvalPreset(preset="nonexistent", track=False)


def test_qa_suite_wrappers_use_presets():
    assert (
        SummarizationConsistencyJudge(track=False).task_introduction
        == GEVAL_PRESETS["summarization_consistency"].task_introduction
    )
    assert (
        SummarizationCoherenceJudge(track=False).task_introduction
        == GEVAL_PRESETS["summarization_coherence"].task_introduction
    )
    assert (
        DialogueHelpfulnessJudge(track=False).task_introduction
        == GEVAL_PRESETS["dialogue_helpfulness"].task_introduction
    )
    assert (
        QARelevanceJudge(track=False).task_introduction
        == GEVAL_PRESETS["qa_relevance"].task_introduction
    )


def test_bias_and_agent_wrapper_presets():
    assert (
        DemographicBiasJudge(track=False).task_introduction
        == GEVAL_PRESETS["bias_demographic"].task_introduction
    )
    assert (
        PoliticalBiasJudge(track=False).evaluation_criteria
        == GEVAL_PRESETS["bias_political"].evaluation_criteria
    )
    assert (
        GenderBiasJudge(track=False).evaluation_criteria
        == GEVAL_PRESETS["bias_gender"].evaluation_criteria
    )
    assert (
        ReligiousBiasJudge(track=False).evaluation_criteria
        == GEVAL_PRESETS["bias_religion"].evaluation_criteria
    )
    assert (
        RegionalBiasJudge(track=False).task_introduction
        == GEVAL_PRESETS["bias_regional"].task_introduction
    )
    assert (
        AgentToolCorrectnessJudge(track=False).evaluation_criteria
        == GEVAL_PRESETS["agent_tool_correctness"].evaluation_criteria
    )
    assert (
        AgentTaskCompletionJudge(track=False).task_introduction
        == GEVAL_PRESETS["agent_task_completion"].task_introduction
    )


def test_prompt_wrapper_presets():
    assert (
        PromptUncertaintyJudge(track=False).evaluation_criteria
        == GEVAL_PRESETS["prompt_uncertainty"].evaluation_criteria
    )


def test_compliance_wrapper_preset():
    assert (
        ComplianceRiskJudge(track=False).evaluation_criteria
        == GEVAL_PRESETS["compliance_regulated_truthfulness"].evaluation_criteria
    )
