from typing import Any, Dict, List

from opik.evaluation.metrics import (
    AnswerRelevance,
    BaseMetric,
#    ContextPrecision,
#    ContextRecall,
    Equals,
    Hallucination,
    LevenshteinRatio,
)
from pydantic import BaseModel


class BenchmarkDatasetConfig(BaseModel):
    model_config = {"arbitrary_types_allowed":True}
    
    name: str
    display_name: str
    metrics: List[BaseMetric]
    input_key: str
    output_key: str

class BenchmarkProjectConfig(BaseModel):
    name: str
    workspace: str
    test_mode: bool

class BenchmarkOptimizerConfig(BaseModel):
    class_name: str
    params: Dict[str, Any]


class BenchmarkExperimentConfig(BaseModel):
    dataset_name: str
    optimizer: str
    model_name: str
    timestamp: str
    test_mode: bool
    environment: Dict[str, Any]
    parameters: Dict[str, Any]
    metrics: List[str]


DATASET_CONFIG = {
    "gsm8k": BenchmarkDatasetConfig(
        name="gsm8k",
        display_name="GSM8K",
        metrics=[LevenshteinRatio()],
        input_key="question",
        output_key="answer",
    ),
    "ragbench_sentence_relevance": BenchmarkDatasetConfig(
        name="ragbench_sentence_relevance",
        display_name="RAGBench Sentence Relevance",
        metrics=[AnswerRelevance(require_context=False)],
        input_key="question",
        output_key="sentence",
    ),
    # "election_questions": BenchmarkDatasetConfig(
    #     name="election_questions",
    #     display_name="Election Questions",
    #     metrics=[Hallucination()],
    #     input_key="question",
    #     output_key="label"
    # ),
    "medhallu": BenchmarkDatasetConfig(
        name="MedHallu",
        display_name="MedHallu",
        metrics=[Hallucination(), AnswerRelevance(require_context=False)],
        input_key="question",
        output_key="ground_truth",
    ),
    # "rag_hallucinations": BenchmarkDatasetConfig(
    #     name="rag_hallucinations",
    #     display_name="RAG Hallucinations",
    #     metrics=[Hallucination(), ContextPrecision()],
    #     input_key="question",
    #     output_key="answer",
    # ),
    # "hotpot_300": BenchmarkDatasetConfig(
    #     name="hotpot_300",
    #     display_name="HotpotQA",
    #     metrics=[AnswerRelevance(), ContextPrecision()],
    #     input_key="question",
    #     output_key="answer",
    # ),
    "ai2_arc": BenchmarkDatasetConfig(
        name="ai2_arc",
        display_name="ARC",
        metrics=[Equals()],
        input_key="question",
        output_key="answer",
    ),
    # "truthful_qa": BenchmarkDatasetConfig(
    #     name="TruthfulQA",
    #     display_name="TruthfulQA",
    #     metrics=[Hallucination(), AnswerRelevance()],
    #     input_key="question",
    #     output_key="answer",
    # ),
    # "cnn_dailymail": BenchmarkDatasetConfig(
    #     name="cnn_dailymail",
    #     display_name="CNN/Daily Mail",
    #     metrics=[LevenshteinRatio(), ContextRecall()],
    #     input_key="article",
    #     output_key="highlights",
    # ),
}

OPTIMIZER_CONFIGS: Dict[str, BenchmarkOptimizerConfig] = {
    "few_shot": BenchmarkOptimizerConfig(
        class_name="FewShotBayesianOptimizer",
        params={
            "min_examples": 2,
            "max_examples": 7,
            "n_threads": 4,
            "n_trials": 10,
            "n_samples": 100,
            "seed": 42,
        },
    ),
    "meta_prompt": BenchmarkOptimizerConfig(
        class_name="MetaPromptOptimizer",
        params={
            "max_rounds": 3,
            "num_prompts_per_round": 4,
            "temperature": 0.1,
            "max_completion_tokens": 9000,
            "num_threads": 5,
            "seed": 42,
        },
    ),
    # "mipro": BenchmarkOptimizerConfig(
    #     class_name="MiproOptimizer",
    #     params={
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 10,
    #         "seed": 42,
    #     },
    # ),
    "evolutionary_optimizer": BenchmarkOptimizerConfig(
        class_name="EvolutionaryOptimizer",
        params={
            "population_size": 10,
            "num_generations": 4,
            "mutation_rate": 0.2,
            "crossover_rate": 0.8,
            "tournament_size": 4,
            "num_threads": 4,
            "elitism_size": 2,
            "adaptive_mutation": True,
            "enable_moo": False,
            "enable_llm_crossover": False,
            "seed": 42,
            "infer_output_style": True
        }
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
    "gsm8k": [{"role": "system", "content": "Solve the following math problem step by step."}, {"role": "user", "content": "{question}"}],
    "ragbench_sentence_relevance": [{"role": "system", "content": "Evaluate whether the given sentence is relevant to answering the question."}, {"role": "user", "content": "Question: {question}\nSentence: {sentence}"}],
    "election_questions": [{"role": "system", "content": "Classify whether the following question about US elections is harmful or harmless."}, {"role": "user", "content": "{question}"}],
    "medhallu": [{"role": "system", "content": "Answer the medical question accurately based on the given knowledge, avoiding any hallucinations."}, {"role": "user", "content": "{question}"}],
    "rag_hallucinations": [{"role": "system", "content": "Answer the question based on the given context, ensuring all information is supported by the context."}, {"role": "user", "content": "{question}"}],
    "hotpotqa": [{"role": "system", "content": "Answer the question based on the given context."}, {"role": "user", "content": "{question}"}],
    "ai2_arc": [{"role": "system", "content": "Select the correct answer from the given options."}, {"role": "user", "content": "Question: {question}\nChoices: {choices}"}],
    "truthfulqa": [{"role": "system", "content": "Provide a truthful and accurate answer to the question."}, {"role": "user", "content": "{question}"}],
    "cnn_dailymail": [{"role": "system", "content": "Summarize the following article concisely."}, {"role": "user", "content": "{article}"}],
}
