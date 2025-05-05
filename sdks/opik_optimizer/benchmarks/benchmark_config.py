from typing import Dict, List, Tuple, Callable, Any
import sys

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

# Project configuration
PROJECT_CONFIG = {
    "name": "agent-optimizer-benchmark",
    "workspace": "default",
    "test_mode": False,  # Set to True to run with 5 examples per dataset
}

# Dataset configurations
DATASET_CONFIGS = {
    "gsm8k": {
        "name": "GSM8K",
        "metrics": [LevenshteinRatio()],
        "input_key": "question",
        "output_key": "answer",
        "huggingface_path": "gsm8k",
    },
    "ragbench_sentence_relevance": {
        "name": "RAGBench Sentence Relevance",
        "metrics": [AnswerRelevance()],
        "input_key": "question",
        "output_key": "sentence",
        "huggingface_path": "ragbench_sentence_relevance",
    },
    "election_questions": {
        "name": "Election Questions",
        "metrics": [Hallucination()],
        "input_key": "question",
        "output_key": "label",
        "huggingface_path": "election_questions",
    },
    "medhallu": {
        "name": "MedHallu",
        "metrics": [Hallucination(), AnswerRelevance()],
        "input_key": "question",
        "output_key": "ground_truth",
        "huggingface_path": "medhallu",
    },
    "rag_hallucinations": {
        "name": "RAG Hallucinations",
        "metrics": [Hallucination(), ContextPrecision()],
        "input_key": "question",
        "output_key": "answer",
        "huggingface_path": "rag_hallucinations",
    },
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
    # "truthfulqa": {
    #     "name": "TruthfulQA",
    #     "metrics": [Hallucination(), AnswerRelevance()],
    #     "input_key": "question",
    #     "output_key": "answer",
    #     "huggingface_path": "truthful_qa",
    # },
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
    "few_shot": {
        "class": "FewShotBayesianOptimizer",
        "params": {
            "model": "gpt-4o-mini",
            "min_examples": 3,
            "max_examples": 8,
            "n_threads": 4,
            "seed": 42,
        },
    },
    "meta_prompt": {
        "class": "MetaPromptOptimizer",
        "params": {
            "model": "gpt-4o-mini",
            "max_rounds": 3,
            "num_prompts_per_round": 4,
            "improvement_threshold": 0.01,
            "temperature": 0.1,
            "max_completion_tokens": 5000,
            "num_threads": 4,
        },
    },
    "mipro": {
        "class": "MiproOptimizer",
        "params": {
            "model": "gpt-4o-mini",
            "temperature": 0.1,
            "max_tokens": 5000,
            "num_threads": 4,
        },
    },
    # "external_dspy_mipro": {
    #     "class": "ExternalDspyMiproOptimizer",
    #     "params": {
    #         "model": "openai/gpt-4o-mini",
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 1,
    #     },
    # },
    # "external_adalflow": {
    #     "class": "ExternalAdalFlowOptimizer",
    #     "params": {
    #         "model": "gpt-4o-mini",
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 1,
    #     },
    # },
}

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


def get_experiment_config(dataset_name: str, optimizer_name: str) -> Dict:
    """Get experiment configuration with metadata."""
    version_info = sys.version_info
    return {
        "dataset": dataset_name,
        "optimizer": optimizer_name,
        "timestamp": datetime.now().isoformat(),
        "test_mode": PROJECT_CONFIG["test_mode"],  # Include test mode in experiment config
        "environment": {
            "python_version": "{}.{}.{}".format(
                version_info.major, version_info.minor, version_info.micro
            ),
            "opik_version": opik_optimizer.__version__,
        },
        "parameters": {
            "model": OPTIMIZER_CONFIGS[optimizer_name]["params"]["model"],
            "temperature": OPTIMIZER_CONFIGS[optimizer_name]["params"].get(
                "temperature", 0.1
            ),
            "max_tokens": OPTIMIZER_CONFIGS[optimizer_name]["params"].get(
                "max_tokens", 5000
            ),
        },
        "metrics": [str(m) for m in DATASET_CONFIGS[dataset_name]["metrics"]],
    }


class OptimizationMonitor:
    """Monitor optimization progress and generate visualizations."""

    def __init__(self, output_dir: str = "benchmark_results"):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.metrics_history = []
        self.prompts_history = []

    def callback(self, result: Dict[str, Any], step: int, total_steps: int):
        """Callback function for monitoring optimization progress."""
        metrics = {
            "step": step,
            "total_steps": total_steps,
            "timestamp": datetime.now().isoformat(),
        }

        # Extract metrics from result
        if isinstance(result, dict) and "scores" in result:
            for metric_name, score in result["scores"].items():
                metrics[metric_name] = score
        elif isinstance(result, (int, float)):
            metrics["score"] = result

        self.metrics_history.append(metrics)

        # Save intermediate results
        self.save_progress()

    def save_progress(self):
        """Save progress to files."""
        if not self.metrics_history:
            return

        # Save metrics history
        df = pd.DataFrame(self.metrics_history)
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")

        # Save CSV
        csv_path = self.output_dir / f"metrics_history_{timestamp}.csv"
        df.to_csv(csv_path, index=False)

        # Generate and save plots
        self.generate_plots(df, timestamp)

    def generate_plots(self, df: pd.DataFrame, timestamp: str):
        """Generate visualization plots."""
        # Use a valid matplotlib style
        plt.style.use("default")
        
        # Plot metrics over time
        for metric in df.columns:
            if metric not in ["step", "total_steps", "timestamp"]:
                plt.figure(figsize=(10, 6))
                sns.lineplot(data=df, x="step", y=metric)
                plt.title(f"{metric} over Optimization Steps")
                plt.xlabel("Step")
                plt.ylabel("Score")
                plt.savefig(self.output_dir / f"{metric}_history_{timestamp}.png")
                plt.close()

        # Plot all metrics together
        plt.figure(figsize=(12, 8))
        for metric in df.columns:
            if metric not in ["step", "total_steps", "timestamp"]:
                sns.lineplot(data=df, x="step", y=metric, label=metric)
        plt.title("All Metrics over Optimization Steps")
        plt.xlabel("Step")
        plt.ylabel("Score")
        plt.legend()
        plt.savefig(self.output_dir / f"all_metrics_history_{timestamp}.png")
        plt.close()


def get_optimization_monitor(
    output_dir: str = "benchmark_results",
) -> OptimizationMonitor:
    """Get an instance of the optimization monitor."""
    return OptimizationMonitor(output_dir)
