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


class BenchmarkDatasetConfig(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    name: str
    display_name: str
    metrics: list[Callable]


class BenchmarkProjectConfig(BaseModel):
    name: str
    workspace: str
    test_mode: bool


class BenchmarkOptimizerConfig(BaseModel):
    class_name: str
    params: dict[str, Any]
    optimize_params: dict[str, Any] = {}


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


DATASET_CONFIG = {
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
    "hotpot_300": BenchmarkDatasetConfig(
        name="hotpot_300",
        display_name="HotpotQA",
        metrics=[create_answer_relevance_metric("question")],
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
        optimize_params={
            "max_trials": 30,
            "n_samples": 100,
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
        optimize_params={
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
        optimize_params={
            "max_trials": 30,
            "population_size": 10,
            "num_generations": 4,
        },
    ),
    "hierarchical_reflective": BenchmarkOptimizerConfig(
        class_name="HierarchicalReflectiveOptimizer",
        params={
            "n_threads": 4,
            "max_parallel_batches": 5,
            "batch_size": 25,
            "convergence_threshold": 0.01,
            "seed": 42,
        },
        optimize_params={
            "max_trials": 30,
        },
    ),
}

MODELS = [
    # Standard models
    # "openai/gpt-4.1-2025-04-14",
    "openai/gpt-4o-mini",
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
