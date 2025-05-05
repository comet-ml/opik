import json
import time
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from functools import lru_cache
import argparse

import pandas as pd
from datasets import load_dataset
from tqdm import tqdm

from opik_optimizer import (
    FewShotBayesianOptimizer,
    MetaPromptOptimizer,
    MiproOptimizer,
    OptimizationConfig,
    MetricConfig,
    PromptTaskConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.demo import get_or_create_dataset
from opik_optimizer.cache_config import initialize_cache, clear_cache

from benchmark_config import (
    DATASET_CONFIGS, 
    OPTIMIZER_CONFIGS, 
    INITIAL_PROMPTS,
    PROJECT_CONFIG,
    get_experiment_config,
    get_optimization_monitor
)

class BenchmarkRunner:
    def __init__(self, output_dir: str = "benchmark_results", max_workers: int = 4, seed: int = 42):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.results = []
        self.monitor = get_optimization_monitor(output_dir)
        self.max_workers = max_workers
        self.dataset_cache = {}
        self.seed = seed
        self.project_name = PROJECT_CONFIG["name"]
        self.workspace = PROJECT_CONFIG["workspace"]
        
        # Set global random seed
        import random
        import numpy as np
        random.seed(seed)
        np.random.seed(seed)
        
        # Initialize shared cache
        initialize_cache()
        
    @lru_cache(maxsize=32)
    def load_dataset(self, dataset_name: str, huggingface_path: str) -> Any:
        """Load dataset from HuggingFace or create if not exists."""
        try:
            return get_or_create_dataset(huggingface_path)
        except Exception as e:
            print(f"Error loading dataset {dataset_name}: {e}")
            print(traceback.format_exc())
            return None

    def create_optimizer(self, optimizer_config: Dict, project_name: str) -> Any:
        """Create optimizer instance based on configuration."""
        try:
            # Handle external optimizers
            if optimizer_config["class"] == "ExternalDspyMiproOptimizer":
                params = optimizer_config["params"].copy()
                params["seed"] = self.seed
                params["project_name"] = self.project_name  # Pass project name
                # Remove workspace as it's not supported
                params.pop("workspace", None)
                return ExternalDspyMiproOptimizer(**params)
            elif optimizer_config["class"] == "ExternalAdalFlowOptimizer":
                params = optimizer_config["params"].copy()
                params["seed"] = self.seed
                params["project_name"] = self.project_name
                params.pop("workspace", None)
                return ExternalAdalFlowOptimizer(**params)
            
            # Handle internal optimizers
            optimizer_class = globals()[optimizer_config["class"]]
            params = optimizer_config["params"].copy()
            params["project_name"] = self.project_name
            params.pop("workspace", None)
            params["seed"] = self.seed
            return optimizer_class(**params)
        except Exception as e:
            print(f"Error creating optimizer {optimizer_config['class']}: {e}")
            print(traceback.format_exc())
            return None

    def run_optimization(self, 
                        dataset: Any,
                        optimizer: Any,
                        metrics: List,
                        initial_prompt: str,
                        input_key: str,
                        output_key: str,
                        experiment_config: Dict) -> Dict:
        """Run optimization and return results."""
        if dataset is None or optimizer is None:
            return None
            
        start_time = time.time()
        
        try:
            # Create optimization config
            config = OptimizationConfig(
                dataset=dataset,
                objective=MetricConfig(
                    metric=metrics[0],
                    inputs={
                        "output": from_llm_response_text(),
                        "reference": from_dataset_field(name=output_key),
                    }
                ),
                task=PromptTaskConfig(
                    instruction_prompt=initial_prompt,
                    input_dataset_fields=[input_key],
                    output_dataset_field=output_key,
                    use_chat_prompt=isinstance(optimizer, FewShotBayesianOptimizer),
                )
            )

            # Evaluate initial prompt based on optimizer type
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                initial_scores = {}
                future_to_metric = {}
                
                for metric in metrics:
                    if isinstance(optimizer, MetaPromptOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=config.objective,
                            task_config=config.task,
                            prompt=initial_prompt,
                            experiment_config=experiment_config,
                        )
                    elif isinstance(optimizer, MiproOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            config=config,
                            prompt=initial_prompt,
                            experiment_config=experiment_config,
                        )
                    elif isinstance(optimizer, FewShotBayesianOptimizer):
                        chat_prompt = [
                            {"role": "system", "content": initial_prompt},
                            {"role": "user", "content": f"{{{input_key}}}"},
                        ]
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=config.objective,
                            prompt=chat_prompt,
                            experiment_config=experiment_config,
                        )
                    else:
                        raise ValueError(f"Unsupported optimizer type: {type(optimizer).__name__}")
                    future_to_metric[future] = metric
                
                for future in as_completed(future_to_metric):
                    metric = future_to_metric[future]
                    try:
                        score = future.result()
                        initial_scores[str(metric)] = score
                    except Exception as e:
                        print(f"Error evaluating initial prompt for {metric}: {e}")
                        initial_scores[str(metric)] = 0.0

            # Run optimization based on optimizer type
            if isinstance(optimizer, (MetaPromptOptimizer, MiproOptimizer)):
                results = optimizer.optimize_prompt(config=config)
            elif isinstance(optimizer, FewShotBayesianOptimizer):
                results = optimizer.optimize_prompt(config=config, n_trials=10)
            else:
                raise ValueError(f"Unsupported optimizer type: {type(optimizer).__name__}")

            # Evaluate final prompt based on optimizer type
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                final_scores = {}
                future_to_metric = {}
                
                for metric in metrics:
                    if isinstance(optimizer, MetaPromptOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=config.objective,
                            task_config=config.task,
                            prompt=results.prompt,
                            experiment_config=experiment_config,
                        )
                    elif isinstance(optimizer, MiproOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            config=config,
                            prompt=results.prompt,
                            experiment_config=experiment_config,
                        )
                    elif isinstance(optimizer, FewShotBayesianOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=config.objective,
                            prompt=results.prompt,
                            experiment_config=experiment_config,
                        )
                    else:
                        raise ValueError(f"Unsupported optimizer type: {type(optimizer).__name__}")
                    future_to_metric[future] = metric
                
                for future in as_completed(future_to_metric):
                    metric = future_to_metric[future]
                    try:
                        score = future.result()
                        final_scores[str(metric)] = score
                    except Exception as e:
                        print(f"Error evaluating final prompt for {metric}: {e}")
                        final_scores[str(metric)] = 0.0

            end_time = time.time()
            
            return {
                "initial_prompt": initial_prompt,
                "final_prompt": results.prompt,
                "initial_scores": initial_scores,
                "final_scores": final_scores,
                "time_taken": end_time - start_time,
                "experiment_config": experiment_config,
            }
        except Exception as e:
            print(f"Error during optimization: {e}")
            print(traceback.format_exc())
            return None

    def run_benchmark(self, 
                     datasets: List[str] = None,
                     optimizers: List[str] = None,
                     num_samples: int = 100):
        """Run benchmark across specified datasets and optimizers."""
        if datasets is None:
            datasets = list(DATASET_CONFIGS.keys())
        if optimizers is None:
            optimizers = list(OPTIMIZER_CONFIGS.keys())

        # Preload all datasets
        print("Preloading datasets...")
        for dataset_key in datasets:
            dataset_config = DATASET_CONFIGS[dataset_key]
            self.dataset_cache[dataset_key] = self.load_dataset(
                dataset_config["name"],
                dataset_config["huggingface_path"]
            )

        # Run optimizations in parallel
        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            futures = []
            
            for dataset_key in datasets:
                dataset = self.dataset_cache[dataset_key]
                if dataset is None:
                    continue

                for optimizer_key in optimizers:
                    project_name = f"benchmark-{dataset_key}-{optimizer_key}-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
                    optimizer = self.create_optimizer(OPTIMIZER_CONFIGS[optimizer_key], project_name)
                    if optimizer is None:
                        continue
                    
                    experiment_config = get_experiment_config(dataset_key, optimizer_key)
                    
                    future = executor.submit(
                        self.run_optimization,
                        dataset=dataset,
                        optimizer=optimizer,
                        metrics=DATASET_CONFIGS[dataset_key]["metrics"],
                        initial_prompt=INITIAL_PROMPTS[dataset_key],
                        input_key=DATASET_CONFIGS[dataset_key]["input_key"],
                        output_key=DATASET_CONFIGS[dataset_key]["output_key"],
                        experiment_config=experiment_config,
                    )
                    futures.append((dataset_key, optimizer_key, future))

            # Process results as they complete
            for dataset_key, optimizer_key, future in tqdm(futures, desc="Running optimizations"):
                try:
                    result = future.result()
                    if result is not None:
                        self.results.append({
                            "dataset": DATASET_CONFIGS[dataset_key]["name"],
                            "optimizer": optimizer_key,
                            **result
                        })
                        self.save_results()
                except Exception as e:
                    print(f"Error in optimization for {dataset_key} with {optimizer_key}: {e}")
                    print(traceback.format_exc())

    def save_results(self):
        """Save results to JSON and CSV files."""
        if not self.results:
            return
            
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        
        # Save as JSON
        json_path = self.output_dir / f"results_{timestamp}.json"
        with open(json_path, "w") as f:
            json.dump(self.results, f, indent=2)
        
        # Save as CSV
        df = pd.DataFrame(self.results)
        csv_path = self.output_dir / f"results_{timestamp}.csv"
        df.to_csv(csv_path, index=False)

    def print_summary(self):
        """Print summary of results."""
        if not self.results:
            print("No results to summarize")
            return
            
        df = pd.DataFrame(self.results)
        
        # Calculate improvements
        for metric in df["initial_scores"].iloc[0].keys():
            df[f"{metric}_improvement"] = df.apply(
                lambda x: x["final_scores"][metric] - x["initial_scores"][metric], 
                axis=1
            )
        
        # Group by dataset and optimizer
        summary = df.groupby(["dataset", "optimizer"]).agg({
            **{f"{metric}_improvement": "mean" for metric in df["initial_scores"].iloc[0].keys()},
            "time_taken": "mean"
        }).round(3)
        
        print("\nBenchmark Summary:")
        print(summary)
        
        # Generate final visualizations
        self.monitor.generate_plots(
            pd.DataFrame(self.monitor.metrics_history),
            "final"
        )

def run_benchmark(
    dataset_name: str,
    optimizer_name: str,
    num_iterations: int = 3,
    test_mode: bool = False,
) -> Dict:
    """Run benchmark for a single dataset and optimizer."""
    print(f"\nRunning benchmark for {dataset_name} with {optimizer_name}")
    print(f"Test mode: {test_mode}")

    # Get dataset
    dataset = get_or_create_dataset(dataset_name, test_mode=test_mode)
    items = dataset.get_items()
    print(f"Loaded dataset with {len(items)} examples")

    # Create benchmark runner and optimizer
    runner = BenchmarkRunner()
    optimizer = runner.create_optimizer(OPTIMIZER_CONFIGS[optimizer_name], PROJECT_CONFIG["name"])
    if optimizer is None:
        raise Exception(f"Failed to create optimizer {optimizer_name}")

    # Get metrics and config
    metrics = DATASET_CONFIGS[dataset_name]["metrics"]
    input_key = DATASET_CONFIGS[dataset_name]["input_key"]
    output_key = DATASET_CONFIGS[dataset_name]["output_key"]
    initial_prompt = INITIAL_PROMPTS[dataset_name]
    experiment_config = get_experiment_config(dataset_name, optimizer_name)

    # Create optimization config
    config = OptimizationConfig(
        dataset=dataset,
        objective=MetricConfig(
            metric=metrics[0],
            inputs={
                "output": from_llm_response_text(),
                "reference": from_dataset_field(name=output_key),
            }
        ),
        task=PromptTaskConfig(
            instruction_prompt=initial_prompt,
            input_dataset_fields=[input_key],
            output_dataset_field=output_key,
            use_chat_prompt=isinstance(optimizer, FewShotBayesianOptimizer),
        )
    )

    # Run iterations
    results = []
    for i in range(num_iterations):
        print(f"\nIteration {i+1}/{num_iterations}")
        
        # Run optimization
        start_time = time.time()
        try:
            if isinstance(optimizer, (MetaPromptOptimizer, MiproOptimizer)):
                optimization_result = optimizer.optimize_prompt(config=config)
            elif isinstance(optimizer, FewShotBayesianOptimizer):
                optimization_result = optimizer.optimize_prompt(config=config, n_trials=10)
            else:
                raise ValueError(f"Unsupported optimizer type: {type(optimizer).__name__}")
            
            optimized_prompt = optimization_result.prompt
            optimization_time = time.time() - start_time

            # Evaluate optimized prompt
            start_time = time.time()
            if isinstance(optimizer, MetaPromptOptimizer):
                optimized_scores = optimizer.evaluate_prompt(
                    dataset=dataset,
                    metric_config=config.objective,
                    task_config=config.task,
                    prompt=optimized_prompt,
                    experiment_config=experiment_config,
                )
            elif isinstance(optimizer, MiproOptimizer):
                optimized_scores = optimizer.evaluate_prompt(
                    dataset=dataset,
                    config=config,
                    prompt=optimized_prompt,
                    experiment_config=experiment_config,
                )
            elif isinstance(optimizer, FewShotBayesianOptimizer):
                optimized_scores = optimizer.evaluate_prompt(
                    dataset=dataset,
                    metric_config=config.objective,
                    prompt=optimized_prompt,
                    experiment_config=experiment_config,
                )
            else:
                raise ValueError(f"Unsupported optimizer type: {type(optimizer).__name__}")
            
            evaluation_time = time.time() - start_time

            results.append({
                "iteration": i + 1,
                "optimized_prompt": optimized_prompt,
                "optimized_scores": optimized_scores,
                "optimization_time": optimization_time,
                "evaluation_time": evaluation_time,
            })
        except Exception as e:
            print(f"Error in iteration {i+1}: {e}")
            print(traceback.format_exc())
            continue

    return {
        "dataset": dataset_name,
        "optimizer": optimizer_name,
        "test_mode": test_mode,
        "num_iterations": num_iterations,
        "results": results,
    }

def main():
    """Run benchmarks for all datasets and optimizers."""
    # Parse command line arguments
    parser = argparse.ArgumentParser(description="Run benchmarks")
    parser.add_argument(
        "--datasets",
        nargs="+",
        default=list(DATASET_CONFIGS.keys()),
        help="Datasets to run benchmarks for",
    )
    parser.add_argument(
        "--optimizers",
        nargs="+",
        default=list(OPTIMIZER_CONFIGS.keys()),
        help="Optimizers to run benchmarks for",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=3,
        help="Number of iterations to run for each benchmark",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        help="Run in test mode with 5 examples per dataset",
    )
    args = parser.parse_args()

    # Run benchmarks
    results = []
    for dataset_name in args.datasets:
        for optimizer_name in args.optimizers:
            try:
                result = run_benchmark(
                    dataset_name=dataset_name,
                    optimizer_name=optimizer_name,
                    num_iterations=args.iterations,
                    test_mode=args.test_mode,
                )
                results.append(result)
            except Exception as e:
                print(f"Error running benchmark for {dataset_name} with {optimizer_name}: {e}")
                print(traceback.format_exc())
                continue

    # Save results
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    test_mode_str = "_test" if args.test_mode else ""
    output_file = f"benchmark_results{test_mode_str}_{timestamp}.json"
    with open(output_file, "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nResults saved to {output_file}")

if __name__ == "__main__":
    main() 