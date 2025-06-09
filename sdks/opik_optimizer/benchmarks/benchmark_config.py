from typing import Any, Callable, Dict, List

from opik.evaluation.metrics import (
    AnswerRelevance,
    ContextPrecision,
    ContextRecall,
    Equals,
    Hallucination,
    LevenshteinRatio,
)
from pydantic import BaseModel


class BenchmarkDatasetConfig(BaseModel):
    model_config = {"arbitrary_types_allowed":True}
    
    name: str
    display_name: str
    metrics: List[Callable]

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

def levenshtein_ratio(dataset_item, llm_output):
    return LevenshteinRatio().score(reference=dataset_item['answer'], output=llm_output)

def equals(dataset_item, llm_output):
    return Equals().score(reference=dataset_item['answer'], output=llm_output)

def create_answer_relevance_metric(name_input_col):
    def answer_relevance(dataset_item, llm_output):
        return AnswerRelevance(require_context=False).score(input=dataset_item[name_input_col], output=llm_output)
    return answer_relevance

def create_context_precision(name_input_col):
    def context_precision(dataset_item, llm_output):
        return ContextPrecision().score(input=dataset_item[name_input_col], output=llm_output)
    return context_precision

def create_context_recall(name_input_col):
    def context_recall(dataset_item, llm_output):
        return ContextRecall().score(input=dataset_item[name_input_col], output=llm_output)
    return context_recall

def hallucination(dataset_item, llm_output):
    return Hallucination().score(input=dataset_item["question"], output=llm_output)


DATASET_CONFIG = {
    "gsm8k": BenchmarkDatasetConfig(
        name="gsm8k",
        display_name="GSM8K",
        metrics=[levenshtein_ratio]
    ),
    "ragbench_sentence_relevance": BenchmarkDatasetConfig(
        name="ragbench_sentence_relevance",
        display_name="RAGBench Sentence Relevance",
        metrics=[create_answer_relevance_metric("question")]
    ),
    "election_questions": BenchmarkDatasetConfig(
        name="election_questions",
        display_name="Election Questions",
        metrics=[hallucination]
    ),
    "medhallu": BenchmarkDatasetConfig(
        name="MedHallu",
        display_name="MedHallu",
        metrics=[hallucination, create_answer_relevance_metric("question")]
    ),
    "rag_hallucinations": BenchmarkDatasetConfig(
        name="rag_hallucinations",
        display_name="RAG Hallucinations",
        metrics=[hallucination, create_context_precision("question")]
    ),
    "hotpot_300": BenchmarkDatasetConfig(
        name="hotpot_300",
        display_name="HotpotQA",
        metrics=[create_answer_relevance_metric("question"), create_context_precision("question")]
    ),
    "ai2_arc": BenchmarkDatasetConfig(
        name="ai2_arc",
        display_name="ARC",
        metrics=[equals]
    ),
    "truthful_qa": BenchmarkDatasetConfig(
        name="TruthfulQA",
        display_name="TruthfulQA",
        metrics=[hallucination, create_answer_relevance_metric("question")]
    ),
    "cnn_dailymail": BenchmarkDatasetConfig(
        name="cnn_dailymail",
        display_name="CNN/Daily Mail",
        metrics=[levenshtein_ratio, create_context_recall("article")]
    ),
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
    "hotpot_300": [{"role": "system", "content": "Answer the question based on the given context."}, {"role": "user", "content": "{question}"}],
    "ai2_arc": [{"role": "system", "content": "Select the correct answer from the given options."}, {"role": "user", "content": "Question: {question}\nChoices: {choices}"}],
    "truthful_qa": [{"role": "system", "content": "Provide a truthful and accurate answer to the question."}, {"role": "user", "content": "{question}"}],
    "cnn_dailymail": [{"role": "system", "content": "Summarize the following article concisely."}, {"role": "user", "content": "{article}"}],
}
