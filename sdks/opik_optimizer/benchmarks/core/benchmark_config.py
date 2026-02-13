"""Central registry of datasets, optimizers, models, and initial prompts.

Both the local runner and Modal worker import this module to discover which
datasets are available, which metrics they should be evaluated with, the
default optimizer classes/parameters, and the rollout budgets.
"""

from __future__ import annotations

from typing import Any
from collections.abc import Callable

from opik.evaluation.metrics import (
    AnswerRelevance,
    ContextPrecision,
    ContextRecall,
    Equals,
    Hallucination,
    LevenshteinRatio,
)
from opik.evaluation.metrics.score_result import ScoreResult
from pydantic import BaseModel

from benchmarks.metrics import hotpot, hover, ifbench, pupa


class BenchmarkDatasetConfig(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    name: str
    display_name: str
    metrics: list[Callable]
    rollout_budget: int | None = None
    train_rollout_budget: int | None = None
    # If True, this dataset uses a custom agent (handled in task_runner)
    uses_agent: bool = False


class BenchmarkProjectConfig(BaseModel):
    name: str
    workspace: str
    test_mode: bool


class BenchmarkOptimizerConfig(BaseModel):
    class_name: str
    params: dict[str, Any]
    optimizer_prompt_params: dict[str, Any] = {}


class BenchmarkExperimentConfig(BaseModel):
    dataset_name: str
    optimizer: str
    model_name: str
    timestamp: str
    test_mode: bool
    environment: dict[str, Any]
    parameters: dict[str, Any]
    metrics: list[str]


def create_levenshtein_ratio_metric(reference_col: str) -> Callable:
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        result = LevenshteinRatio().score(
            reference=dataset_item[reference_col], output=llm_output
        )
        return ScoreResult(
            name="levenshtein_ratio",
            value=result.value,
            reason=f"Compared `{dataset_item[reference_col]}` and `{llm_output}` and got `{result.value}`.",
        )

    return levenshtein_ratio


def equals(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    result = Equals().score(reference=dataset_item["answer"], output=llm_output)
    if result.value == 1:
        return ScoreResult(name="equals", value=1, reason="The answer is correct.")
    else:
        return ScoreResult(
            name="equals",
            value=0,
            reason=f"The LLM output is not equal to the answer. Expected `{dataset_item['answer']}` but got `{llm_output}`.",
        )


def create_answer_relevance_metric(name_input_col: str) -> Callable:
    def answer_relevance(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        return AnswerRelevance(require_context=False).score(
            input=dataset_item[name_input_col], output=llm_output
        )

    return answer_relevance


def create_context_precision(
    name_input_col: str, expected_output_col: str, context_col: str
) -> Callable:
    def context_precision(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        return ContextPrecision().score(
            input=dataset_item[name_input_col],
            output=llm_output,
            expected_output=dataset_item[expected_output_col],
            context=[dataset_item[context_col]],
        )

    return context_precision


def create_context_recall(
    name_input_col: str, expected_output_col: str, context_col: str
) -> Callable:
    def context_recall(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        return ContextRecall().score(
            input=dataset_item[name_input_col],
            output=llm_output,
            expected_output=dataset_item[expected_output_col],
            context=[dataset_item[context_col]],
        )

    return context_recall


def hallucination(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    return Hallucination().score(input=dataset_item["question"], output=llm_output)


_HOT_POT_METRICS = [hotpot.hotpot_exact_match, hotpot.hotpot_f1]
_HOVER_METRICS = [hover.hover_label_accuracy, hover.hover_judge_feedback]
_IFBENCH_METRICS = [ifbench.ifbench_compliance_judge]
_PUPA_METRICS = [pupa.pupa_quality_judge, pupa.pupa_leakage_ratio]


DATASET_CONFIG = {
    # TODO: derive this entire structure from metadata defined alongside
    # dataset helpers (names, default metrics, rollout budgets, seed counts,
    # initial prompts, etc.) so the configuration is single-sourced.
    "gsm8k": BenchmarkDatasetConfig(
        name="gsm8k",
        display_name="GSM8K",
        metrics=[create_levenshtein_ratio_metric("answer")],
    ),
    "ragbench_sentence_relevance": BenchmarkDatasetConfig(
        name="ragbench_sentence_relevance",
        display_name="RAGBench Sentence Relevance",
        metrics=[create_answer_relevance_metric("question")],
    ),
    "election_questions": BenchmarkDatasetConfig(
        name="election_questions",
        display_name="Election Questions",
        metrics=[hallucination],
    ),
    "medhallu": BenchmarkDatasetConfig(
        name="MedHallu",
        display_name="MedHallu",
        # metrics=[hallucination, create_answer_relevance_metric("question")],
        metrics=[create_answer_relevance_metric("question")],
    ),
    "rag_hallucinations": BenchmarkDatasetConfig(
        name="rag_hallucinations",
        display_name="RAG Hallucinations",
        metrics=[
            hallucination,
            create_context_precision("question", "answer", "context"),
        ],
    ),
    "ai2_arc": BenchmarkDatasetConfig(
        name="ai2_arc", display_name="ARC", metrics=[equals]
    ),
    "truthful_qa": BenchmarkDatasetConfig(
        name="TruthfulQA",
        display_name="TruthfulQA",
        metrics=[hallucination, create_answer_relevance_metric("question")],
    ),
    "cnn_dailymail": BenchmarkDatasetConfig(
        name="cnn_dailymail",
        display_name="CNN/Daily Mail",
        metrics=[create_levenshtein_ratio_metric("highlights")],
    ),
    "tiny_test": BenchmarkDatasetConfig(
        name="tiny_test",
        display_name="Tiny Test",
        metrics=[create_levenshtein_ratio_metric("label")],
    ),
    "hotpot_train": BenchmarkDatasetConfig(
        name="hotpot_train",
        display_name="HotpotQA Train",
        metrics=_HOT_POT_METRICS,
        rollout_budget=6438,
        train_rollout_budget=737,
        uses_agent=True,
    ),
    "hotpot_validation": BenchmarkDatasetConfig(
        name="hotpot_validation",
        display_name="HotpotQA Validation",
        metrics=_HOT_POT_METRICS,
        rollout_budget=6438,
        train_rollout_budget=737,
        uses_agent=True,
    ),
    "hotpot_test": BenchmarkDatasetConfig(
        name="hotpot_test",
        display_name="HotpotQA Test",
        metrics=_HOT_POT_METRICS,
        rollout_budget=6438,
        train_rollout_budget=737,
        uses_agent=True,
    ),
    "hover_train": BenchmarkDatasetConfig(
        name="hover_train",
        display_name="HoVer Train",
        metrics=_HOVER_METRICS,
        rollout_budget=6858,
        train_rollout_budget=558,
    ),
    "hover_validation": BenchmarkDatasetConfig(
        name="hover_validation",
        display_name="HoVer Validation",
        metrics=_HOVER_METRICS,
        rollout_budget=6858,
        train_rollout_budget=558,
    ),
    "hover_test": BenchmarkDatasetConfig(
        name="hover_test",
        display_name="HoVer Test",
        metrics=_HOVER_METRICS,
        rollout_budget=6858,
        train_rollout_budget=558,
    ),
    "ifbench_train": BenchmarkDatasetConfig(
        name="ifbench_train",
        display_name="IFBench Train",
        metrics=_IFBENCH_METRICS,
        rollout_budget=678,
        train_rollout_budget=79,
    ),
    "ifbench_validation": BenchmarkDatasetConfig(
        name="ifbench_validation",
        display_name="IFBench Validation",
        metrics=_IFBENCH_METRICS,
        rollout_budget=678,
        train_rollout_budget=79,
    ),
    "ifbench_test": BenchmarkDatasetConfig(
        name="ifbench_test",
        display_name="IFBench Test",
        metrics=_IFBENCH_METRICS,
        rollout_budget=678,
        train_rollout_budget=79,
    ),
    "pupa_train": BenchmarkDatasetConfig(
        name="pupa_train",
        display_name="PUPA Train",
        metrics=_PUPA_METRICS,
        rollout_budget=2157,
        train_rollout_budget=269,
    ),
    "pupa_validation": BenchmarkDatasetConfig(
        name="pupa_validation",
        display_name="PUPA Validation",
        metrics=_PUPA_METRICS,
        rollout_budget=2157,
        train_rollout_budget=269,
    ),
    "pupa_test": BenchmarkDatasetConfig(
        name="pupa_test",
        display_name="PUPA Test",
        metrics=_PUPA_METRICS,
        rollout_budget=2157,
        train_rollout_budget=269,
    ),
}

OPTIMIZER_CONFIGS: dict[str, BenchmarkOptimizerConfig] = {
    "few_shot": BenchmarkOptimizerConfig(
        class_name="FewShotBayesianOptimizer",
        params={
            "min_examples": 2,
            "max_examples": 7,
            "n_threads": 4,
            "seed": 42,
        },
        optimizer_prompt_params={
            "max_trials": 30,
            "n_samples": 100,
        },
    ),
    "gepa": BenchmarkOptimizerConfig(
        class_name="GepaOptimizer",
        params={
            "n_threads": 4,
            "verbose": 1,
            "seed": 42,
        },
        optimizer_prompt_params={
            "max_trials": 30,
            "n_samples": 3,
            "reflection_minibatch_size": 3,
            "candidate_selection_strategy": "pareto",
            "skip_perfect_score": True,
        },
    ),
    "meta_prompt": BenchmarkOptimizerConfig(
        class_name="MetaPromptOptimizer",
        params={
            "prompts_per_round": 4,
            "enable_context": True,
            "n_threads": 5,
            "seed": 42,
            "model_parameters": {
                "temperature": 0.1,
                "max_completion_tokens": 9000,
            },
        },
        optimizer_prompt_params={
            "max_trials": 30,
        },
    ),
    "evolutionary_optimizer": BenchmarkOptimizerConfig(
        class_name="EvolutionaryOptimizer",
        params={
            "mutation_rate": 0.2,
            "crossover_rate": 0.8,
            "tournament_size": 4,
            "n_threads": 4,
            "elitism_size": 2,
            "adaptive_mutation": True,
            "enable_moo": False,
            "enable_llm_crossover": False,
            "seed": 42,
            "infer_output_style": True,
        },
        optimizer_prompt_params={
            "max_trials": 30,
            "population_size": 10,
            "num_generations": 4,
        },
    ),
    "hierarchical_reflective": BenchmarkOptimizerConfig(
        class_name="HRPO",  # Alias for HierarchicalReflectiveOptimizer
        params={
            "n_threads": 4,
            "max_parallel_batches": 5,
            "batch_size": 25,
            "convergence_threshold": 0.01,
            "seed": 42,
        },
        optimizer_prompt_params={
            "max_trials": 30,
        },
    ),
    "parameter": BenchmarkOptimizerConfig(
        class_name="ParameterOptimizer",
        params={
            "n_threads": 4,
            "seed": 42,
        },
        optimizer_prompt_params={
            "max_trials": 30,
        },
    ),
}

MODELS = [
    # Standard models
    # "openai/gpt-4.1-2025-04-14",
    "openai/gpt-5-nano",
    # "anthropic/claude-3-5-sonnet-20241022",
    # "openrouter/google/gemini-2.5-flash-preview",
    # # Reasoning models
    # "openai/o3-2025-04-16",
    # "anthropic/claude-3-7-sonnet-20250219",
    # "openrouter/google/gemini-2.5-pro-preview",
]

INITIAL_PROMPTS = {
    "gsm8k": [
        {"role": "system", "content": "Solve the following math problem step by step."},
        {"role": "user", "content": "{question}"},
    ],
    "ragbench_sentence_relevance": [
        {
            "role": "system",
            "content": "Evaluate whether the given sentence is relevant to answering the question.",
        },
        {"role": "user", "content": "Question: {question}\nSentence: {sentence}"},
    ],
    "election_questions": [
        {
            "role": "system",
            "content": "Classify whether the following question about US elections is harmful or harmless.",
        },
        {"role": "user", "content": "{question}"},
    ],
    "medhallu": [
        {
            "role": "system",
            "content": "Answer the medical question accurately based on the given knowledge, avoiding any hallucinations.",
        },
        {"role": "user", "content": "{question}"},
    ],
    "rag_hallucinations": [
        {
            "role": "system",
            "content": "Answer the question based on the given context, ensuring all information is supported by the context.",
        },
        {"role": "user", "content": "{question}"},
    ],
    "hotpot_300": [
        {
            "role": "system",
            "content": "Answer the question based on the given context.",
        },
        {"role": "user", "content": "{question}"},
    ],
    "ai2_arc": [
        {
            "role": "system",
            "content": "Select the correct answer from the given options.",
        },
        {"role": "user", "content": "Question: {question}\nChoices: {choices}"},
    ],
    "truthful_qa": [
        {
            "role": "system",
            "content": "Provide a truthful and accurate answer to the question.",
        },
        {"role": "user", "content": "{question}"},
    ],
    "cnn_dailymail": [
        {"role": "system", "content": "Summarize the following article concisely."},
        {"role": "user", "content": "{article}"},
    ],
}


def _clone_prompt(key: str) -> list[dict[str, str]]:
    return [dict(message) for message in INITIAL_PROMPTS[key]]


_HOVER_PROMPT = [
    {
        "role": "system",
        "content": "Determine whether the claim is supported, refuted, or lacks evidence.",
    },
    {"role": "user", "content": "Claim: {claim}"},
]

_IFBENCH_PROMPT = [
    {
        "role": "system",
        "content": "Answer the user's query and then rewrite the response to satisfy the constraints exactly.",
    },
    {
        "role": "user",
        "content": "Messages:\n{messages}\n\nConstraints:\n{constraint}",
    },
]

_PUPA_PROMPT = [
    {
        "role": "system",
        "content": "Rewrite the user's request to remove sensitive information while preserving intent.",
    },
    {"role": "user", "content": "User query: {user_query}"},
]

INITIAL_PROMPTS.update(
    {
        "hotpot_train": _clone_prompt("hotpot_300"),
        "hotpot_validation": _clone_prompt("hotpot_300"),
        "hotpot_test": _clone_prompt("hotpot_300"),
        "hover_train": _HOVER_PROMPT,
        "hover_validation": _HOVER_PROMPT,
        "hover_test": _HOVER_PROMPT,
        "ifbench_train": _IFBENCH_PROMPT,
        "ifbench_validation": _IFBENCH_PROMPT,
        "ifbench_test": _IFBENCH_PROMPT,
        "pupa_train": _PUPA_PROMPT,
        "pupa_validation": _PUPA_PROMPT,
        "pupa_test": _PUPA_PROMPT,
        "tiny_test": [
            {"role": "system", "content": "Answer the question briefly and correctly."},
            {"role": "user", "content": "{text}"},
        ],
        "tiny_test_train": [
            {"role": "system", "content": "Answer the question briefly and correctly."},
            {"role": "user", "content": "{text}"},
        ],
    }
)
