from typing import Dict, List, Tuple, Callable, Any
import sys
import os
import time

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
def get_project_config(test_mode: bool = False) -> Dict:
    return {
        "name": "agent-optimizer-benchmark",
        "workspace": "default",
        # Set to True to run with 5 examples per dataset
        "test_mode": test_mode,
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
        "metrics": [AnswerRelevance(require_context=False)],
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
        "metrics": [Hallucination(), AnswerRelevance(require_context=False)],
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
    # "meta_prompt": {
    #     "class": "MetaPromptOptimizer",
    #     "params": {
    #         "model": "gpt-4o-mini",
    #         "max_rounds": 3,
    #         "num_prompts_per_round": 4,
    #         "improvement_threshold": 0.01,
    #         "temperature": 0.1,
    #         "max_completion_tokens": 5000,
    #         "num_threads": 4,
    #     },
    # },
    # "mipro": {
    #     "class": "MiproOptimizer",
    #     "params": {
    #         "model": "gpt-4o-mini",
    #         "temperature": 0.1,
    #         "max_tokens": 5000,
    #         "num_threads": 4,
    #     },
    # },
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


def get_experiment_config(dataset_name: str, optimizer_name: str, test_mode: bool = False) -> Dict:
    """Get experiment configuration with metadata."""
    version_info = sys.version_info
    return {
        "dataset": dataset_name,
        "optimizer": optimizer_name,
        "timestamp": datetime.now().isoformat(),
        "test_mode": test_mode,  # Include test mode in experiment config
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
    """Monitor optimization progress and generate plots."""
    
    def __init__(self, output_dir: str):
        self.output_dir = output_dir
        self.metrics_history = []
        self.prompts_history = []
        self.start_time = time.time()
        
        # Create output directory if it doesn't exist
        os.makedirs(output_dir, exist_ok=True)
        
    def callback(self, result: Any, step: int, total_steps: int) -> None:
        """Callback function called after each optimization step."""
        timestamp = datetime.now().isoformat()
        
        # Extract metrics from result
        metrics = {}
        if hasattr(result, "scores"):
            metrics = result.scores
        elif hasattr(result, "score"):
            metrics = {"score": result.score}
            
        # Record metrics
        metrics_entry = {
            "timestamp": timestamp,
            "step": step,
            "total_steps": total_steps,
            "trial": len(self.metrics_history) + 1,
            **metrics
        }
        self.metrics_history.append(metrics_entry)
        
        # Record prompt if available
        if hasattr(result, "prompt"):
            prompt_entry = {
                "timestamp": timestamp,
                "step": step,
                "trial": len(self.prompts_history) + 1,
                "prompt": result.prompt
            }
            self.prompts_history.append(prompt_entry)
            
        # Print progress
        print(f"\nTrial {metrics_entry['trial']} - Step {step}/{total_steps}")
        print(f"Metrics: {metrics}")
        if hasattr(result, "prompt"):
            print(f"Current prompt: {result.prompt[:100]}...")
            
    def save_progress(self) -> None:
        """Save optimization progress to files."""
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        
        # Save metrics history
        if self.metrics_history:
            metrics_df = pd.DataFrame(self.metrics_history)
            metrics_file = os.path.join(self.output_dir, f"metrics_{timestamp}.csv")
            metrics_df.to_csv(metrics_file, index=False)
            
        # Save prompts history
        if self.prompts_history:
            prompts_df = pd.DataFrame(self.prompts_history)
            prompts_file = os.path.join(self.output_dir, f"prompts_{timestamp}.csv")
            prompts_df.to_csv(prompts_file, index=False)
            
        # Generate plots
        self.generate_plots(timestamp)
        
    def generate_plots(self, timestamp: str) -> None:
        """Generate plots from optimization history."""
        if not self.metrics_history:
            return
            
        metrics_df = pd.DataFrame(self.metrics_history)
        
        # Plot metrics over trials
        plt.figure(figsize=(12, 6))
        for metric in metrics_df.columns:
            if metric not in ["timestamp", "step", "total_steps", "trial"]:
                plt.plot(metrics_df["trial"], metrics_df[metric], label=metric)
                
        plt.xlabel("Trial")
        plt.ylabel("Score")
        plt.title("Optimization Progress")
        plt.legend()
        plt.grid(True)
        
        # Save plot
        plot_file = os.path.join(self.output_dir, f"optimization_progress_{timestamp}.png")
        plt.savefig(plot_file)
        plt.close()
        
        # Plot metrics over time
        plt.figure(figsize=(12, 6))
        metrics_df["timestamp"] = pd.to_datetime(metrics_df["timestamp"])
        for metric in metrics_df.columns:
            if metric not in ["timestamp", "step", "total_steps", "trial"]:
                plt.plot(metrics_df["timestamp"], metrics_df[metric], label=metric)
                
        plt.xlabel("Time")
        plt.ylabel("Score")
        plt.title("Optimization Progress Over Time")
        plt.legend()
        plt.grid(True)
        plt.xticks(rotation=45)
        
        # Save plot
        time_plot_file = os.path.join(self.output_dir, f"optimization_progress_time_{timestamp}.png")
        plt.savefig(time_plot_file, bbox_inches="tight")
        plt.close()


def get_optimization_monitor(
    output_dir: str = "benchmark_results",
) -> OptimizationMonitor:
    """Get an instance of the optimization monitor."""
    return OptimizationMonitor(output_dir)
