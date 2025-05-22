from typing import Dict, List, Tuple, Callable, Any
import sys
import os
import time
import logging

import opik_optimizer
from opik.evaluation.metrics import (
    LevenshteinRatio,
    AnswerRelevance,
    ContextPrecision,
    ContextRecall,
    Hallucination,
    Equals,
)
import matplotlib.pyplot as plt
import seaborn as sns
import pandas as pd
from datetime import datetime
from pathlib import Path
# from external_optimizers import ExternalDspyMiproOptimizer, ExternalAdalFlowOptimizer
from rich import print
from rich.console import Console
from rich.style import Style

# Project configuration
def get_project_config(test_mode: bool = False) -> Dict:
    return {
        "name": "agent-optimizer-benchmark",
        "workspace": "default",
        # Set to True to run with 5 examples per dataset
        "test_mode": test_mode,
    }

# Dataset configurations
DATASET_CONFIGS = {
    # "gsm8k": {
    #     "name": "GSM8K",
    #     "metrics": [LevenshteinRatio()],
    #     "input_key": "question",
    #     "output_key": "answer",
    #     "huggingface_path": "gsm8k",
    # },
    # "ragbench_sentence_relevance": {
    #     "name": "RAGBench Sentence Relevance",
    #     "metrics": [AnswerRelevance(require_context=False)],
    #     "input_key": "question",
    #     "output_key": "sentence",
    #     "huggingface_path": "ragbench_sentence_relevance",
    # },
    # "election_questions": {
    #     "name": "Election Questions",
    #     "metrics": [Hallucination()],
    #     "input_key": "question",
    #     "output_key": "label",
    #     "huggingface_path": "election_questions",
    # },
    "medhallu": {
        "name": "MedHallu",
        "metrics": [Hallucination(), AnswerRelevance(require_context=False)],
        "input_key": "question",
        "output_key": "ground_truth",
        "huggingface_path": "medhallu",
    },
    # "rag_hallucinations": {
    #     "name": "RAG Hallucinations",
    #     "metrics": [Hallucination(), ContextPrecision()],
    #     "input_key": "question",
    #     "output_key": "answer",
    #     "huggingface_path": "rag_hallucinations",
    # },
    # "hotpotqa": {
    #     "name": "HotpotQA",
    #     "metrics": [AnswerRelevance(), ContextPrecision()],
    #     "input_key": "question",
    #     "output_key": "answer",
    #     "huggingface_path": "hotpot_qa",
    # },
    # "arc": {
    #     "name": "ARC",
    #     "metrics": [Equals()],
    #     "input_key": "question",
    #     "output_key": "answer",
    #     "huggingface_path": "ai2_arc",
    # },
    "truthfulqa": {
        "name": "TruthfulQA",
        "metrics": [Hallucination(), AnswerRelevance()],
        "input_key": "question",
        "output_key": "answer",
        "huggingface_path": "truthful_qa",
    },
    # "cnn_dailymail": {
    #     "name": "CNN/Daily Mail",
    #     "metrics": [LevenshteinRatio(), ContextRecall()],
    #     "input_key": "article",
    #     "output_key": "highlights",
    #     "huggingface_path": "cnn_dailymail",
    # },
}

# Optimizer configurations
OPTIMIZER_CONFIGS = {
    ##############
    # TEST configs
    ##############

    # "few_shot": {
    #     "class": "FewShotBayesianOptimizer",
    #     "params": {
    #         "min_examples": 2,
    #         "max_examples": 3,
    #         "n_threads": 6,
    #         "n_trials": 3,
    #         "n_samples": 100,
    #         "seed": 42,
    #         "verbose": 0,
    #     },
    # },
    # "meta_prompt": {
    #     "class": "MetaPromptOptimizer",
    #     "params": {
    #         "max_rounds": 2,
    #         "num_prompts_per_round": 2,
    #         "improvement_threshold": 0.01,
    #         "temperature": 0.1,
    #         "max_completion_tokens": 9000,
    #         "num_threads": 5,
    #         "subsample_size": 5,
    #         "seed": 42,
    #         "verbose": 0,
    #     },
    # },
    # "mipro": {
    #     "class": "MiproOptimizer",
    #     "params": {
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 10,
    #         "seed": 42,
    #         "verbose": 0,
    #     },
    # },


    ##############
    # Live Benchmark configs
    ##############

    # "few_shot": {
    #     "class": "FewShotBayesianOptimizer",
    #     "params": {
    #         "min_examples": 3,
    #         "max_examples": 7,
    #         "n_threads": 4,
    #         "seed": 42,
    #         "n_trials": 10,
    #         "n_samples": 100,
    #         "verbose": 0,
    #     },
    # },
    "meta_prompt": {
        "class": "MetaPromptOptimizer",
        "params": {
            "max_rounds": 3,
            "num_prompts_per_round": 4,
            "improvement_threshold": 0.01,
            "temperature": 0.1,
            "max_completion_tokens": 9000,
            "num_threads": 5,
            "subsample_size": 10,
            "verbose": 0,
            "enable_context": True,
        },
    },
    "meta_prompt_no_context": {
        "class": "MetaPromptOptimizer",
        "params": {
            "max_rounds": 3,
            "num_prompts_per_round": 4,
            "improvement_threshold": 0.01,
            "temperature": 0.1,
            "max_completion_tokens": 9000,
            "num_threads": 5,
            "subsample_size": 10,
            "verbose": 0,
            "enable_context": False,
        },
    },
    "meta_prompt_single_cot": {
        "class": "MetaPromptOptimizer",
        "params": {
            "max_rounds": 1,
            "num_prompts_per_round": 1,
            "improvement_threshold": 0.01,
            "temperature": 0.1,
            "max_completion_tokens": 9000,
            "num_threads": 5,
            "subsample_size": 10,
            "verbose": 0,
            "enable_context": False,
        },
    },
    # "mipro": {
    #     "class": "MiproOptimizer",
    #     "params": {
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 10,
    #         "verbose": 0,
    #     },
    # },
    # "external_dspy_mipro": {
    #     "class": "ExternalDspyMiproOptimizer",
    #     "params": {
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 1,
    #     },
    # },
    # "external_adalflow": {
    #     "class": "ExternalAdalFlowOptimizer",
    #     "params": {
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 1,
    #     },
    # },
}

MODELS_TO_RUN = [
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

# Initial prompts for each dataset
INITIAL_PROMPTS = {
    "gsm8k": "Solve the following math problem step by step.",
    "ragbench_sentence_relevance": "Evaluate whether the given sentence is relevant to answering the question.",
    "election_questions": "Classify whether the following question about US elections is harmful or harmless.",
    "medhallu": "Answer the medical question accurately based on the given knowledge, avoiding any hallucinations.",
    "rag_hallucinations": "Answer the question based on the given context, ensuring all information is supported by the context.",
    "hotpotqa": "Answer the question based on the given context.",
    "arc": "Select the correct answer from the given options.",
    "truthfulqa": "Provide a truthful and accurate answer to the question.",
    "cnn_dailymail": "Summarize the following article concisely.",
}


def get_experiment_config(dataset_name: str, optimizer_name: str, model_name: str, test_mode: bool = False) -> Dict:
    """Get experiment configuration with metadata."""
    version_info = sys.version_info
    
    # Get base optimizer params and add model_name
    optimizer_params = OPTIMIZER_CONFIGS.get(optimizer_name, {}).get("params", {})
    current_params = optimizer_params.copy()
    current_params["model"] = model_name

    return {
        "dataset": dataset_name,
        "optimizer": optimizer_name,
        "model_name": model_name,
        "timestamp": datetime.now().isoformat(),
        "test_mode": test_mode,  # Include test mode in experiment config
        "environment": {
            "python_version": "{}.{}.{}".format(
                version_info.major, version_info.minor, version_info.micro
            ),
            "opik_version": opik_optimizer.__version__,
        },
        # Store the specific parameters used for this optimizer run
        "parameters": current_params, 
        "metrics": [str(m) for m in DATASET_CONFIGS[dataset_name]["metrics"]],
    }

