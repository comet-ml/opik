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

import opik_optimizer
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
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision

from benchmark_config import (
    DATASET_CONFIGS, 
    OPTIMIZER_CONFIGS, 
    INITIAL_PROMPTS,
    get_project_config,
    get_experiment_config,
    get_optimization_monitor
)

class BenchmarkRunner:
    def __init__(self, output_dir: str = "benchmark_results", max_workers: int = 4, seed: int = 42, test_mode: bool = False):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.results = []
        self.monitor = get_optimization_monitor(output_dir)
        self.max_workers = max_workers
        self.dataset_cache = {}
        self.seed = seed
        self.test_mode = test_mode
        self.project_config = get_project_config(test_mode=test_mode)
        self.project_name = self.project_config["name"]
        self.workspace = self.project_config["workspace"]
        
        # Set global random seed
        import random
        import numpy as np
        random.seed(seed)
        np.random.seed(seed)
        
        # Initialize shared cache
        initialize_cache()
        
        # Create checkpoint directory
        self.checkpoint_dir = self.output_dir / "checkpoints"
        self.checkpoint_dir.mkdir(exist_ok=True)
        
        # Load latest checkpoint if exists
        self.load_latest_checkpoint()

    def pre_cache_datasets(self):
        """Pre-cache all datasets if not already cached."""
        print("\nPre-caching datasets...")
        for dataset_key, dataset_config in DATASET_CONFIGS.items():
            cache_key = f"{dataset_key}_{self.test_mode}"
            if cache_key not in self.dataset_cache:
                try:
                    dataset = self.load_dataset(dataset_key, dataset_config["huggingface_path"])
                    if dataset is not None:
                        print(f"✓ Successfully loaded {dataset_config['name']} with {len(dataset.get_items())} examples")
                    else:
                        print(f"✗ Failed to load {dataset_config['name']}")
                except Exception as e:
                    print(f"✗ Error loading {dataset_config['name']}: {e}")
                    print(traceback.format_exc())
            else:
                print(f"✓ Using cached dataset {dataset_config['name']}")
        print("Dataset pre-caching complete\n")

    def load_latest_checkpoint(self):
        """Load the latest checkpoint if it exists."""
        checkpoint_files = list(self.checkpoint_dir.glob("checkpoint_*.json"))
        if checkpoint_files:
            latest_checkpoint = max(checkpoint_files, key=lambda x: x.stat().st_mtime)
            print(f"Loading checkpoint from {latest_checkpoint}")
            with open(latest_checkpoint, "r") as f:
                checkpoint_data = json.load(f)
                
                # Restore basic state
                self.results = checkpoint_data.get("results", [])
                self.test_mode = checkpoint_data.get("test_mode", False)
                
                # Restore monitor state
                monitor_state = checkpoint_data.get("monitor_state", {})
                self.monitor.metrics_history = monitor_state.get("metrics_history", [])
                self.monitor.prompts_history = monitor_state.get("prompts_history", [])
                
                # Restore project config if it exists
                if "project_config" in checkpoint_data:
                    self.project_config = checkpoint_data["project_config"]
                    self.project_name = self.project_config["name"]
                    self.workspace = self.project_config["workspace"]
                
                # Restore dataset cache keys
                dataset_cache_keys = checkpoint_data.get("dataset_cache_keys", [])
                for key in dataset_cache_keys:
                    if key not in self.dataset_cache:
                        dataset_name = key.split("_")[0]  # Extract dataset name from key
                        if dataset_name in DATASET_CONFIGS:
                            self.load_dataset(dataset_name, DATASET_CONFIGS[dataset_name]["huggingface_path"])
                
                print(f"Loaded {len(self.results)} results from checkpoint")
                print(f"Loaded {len(self.monitor.metrics_history)} metrics history entries")
                print(f"Loaded {len(self.monitor.prompts_history)} prompts history entries")
                print(f"Restored {len(dataset_cache_keys)} dataset cache keys")
                
    def save_checkpoint(self):
        """Save current state to a checkpoint file."""
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        checkpoint_file = self.checkpoint_dir / f"checkpoint_{timestamp}.json"
        
        # Convert optimization history to serializable format
        serialized_results = []
        for result in self.results:
            serialized_result = result.copy()
            if "optimization_details" in serialized_result:
                opt_details = serialized_result["optimization_details"]
                if "optimization_history" in opt_details:
                    # Convert optimization history to list of dicts
                    history = []
                    for step in opt_details["optimization_history"]:
                        if isinstance(step, dict):
                            history.append(step)
                        else:
                            # Convert non-dict history items to dict
                            history.append({
                                "step": getattr(step, "step", None),
                                "score": getattr(step, "score", None),
                                "prompt": getattr(step, "prompt", None),
                                "parameters": getattr(step, "parameters", None),
                                "timestamp": getattr(step, "timestamp", None)
                            })
                    opt_details["optimization_history"] = history
            serialized_results.append(serialized_result)
        
        checkpoint_data = {
            "timestamp": timestamp,
            "test_mode": self.test_mode,
            "project_config": self.project_config,
            "results": serialized_results,
            "dataset_cache_keys": list(self.dataset_cache.keys()),
            "monitor_state": {
                "metrics_history": self.monitor.metrics_history,
                "prompts_history": self.monitor.prompts_history
            },
            "environment": {
                "python_version": sys.version,
                "seed": self.seed,
                "max_workers": self.max_workers
            }
        }
        
        with open(checkpoint_file, "w") as f:
            json.dump(checkpoint_data, f, indent=2)
        print(f"Saved checkpoint to {checkpoint_file}")
        
        # Also save as results file
        results_file = self.output_dir / f"results_{timestamp}.json"
        with open(results_file, "w") as f:
            json.dump(serialized_results, f, indent=2)
        print(f"Saved detailed results to {results_file}")
        
        # Save as CSV with detailed columns
        df = pd.DataFrame(serialized_results)
        # Explode optimization history into separate rows
        history_rows = []
        for _, row in df.iterrows():
            if "optimization_details" in row and row["optimization_details"]:
                history = row["optimization_details"].get("optimization_history", [])
                for step in history:
                    history_row = row.copy()
                    history_row["optimization_step"] = step.get("step")
                    history_row["step_score"] = step.get("score")
                    history_row["step_prompt"] = step.get("prompt")
                    history_row["step_parameters"] = str(step.get("parameters"))
                    history_row["step_timestamp"] = step.get("timestamp")
                    history_rows.append(history_row)
        
        if history_rows:
            history_df = pd.DataFrame(history_rows)
            csv_path = self.output_dir / f"results_{timestamp}_detailed.csv"
            history_df.to_csv(csv_path, index=False)
            print(f"Saved detailed optimization history to {csv_path}")
        
        # Save summary CSV
        summary_df = df.drop(columns=["optimization_details"])
        csv_path = self.output_dir / f"results_{timestamp}_summary.csv"
        summary_df.to_csv(csv_path, index=False)
        print(f"Saved results summary to {csv_path}")

    def load_dataset(self, dataset_name: str, huggingface_path: str) -> Any:
        """Load dataset from HuggingFace or create if not exists."""
        cache_key = f"{dataset_name}_{self.test_mode}"
        if cache_key in self.dataset_cache:
            return self.dataset_cache[cache_key]
            
        try:
            print(f"\nLoading dataset {dataset_name} from {huggingface_path}...")
            dataset = get_or_create_dataset(huggingface_path, test_mode=self.test_mode)
            
            if dataset is None:
                print(f"Failed to load dataset {dataset_name}")
                return None
                
            # Verify dataset has items
            items = dataset.get_items()
            if not items:
                print(f"Warning: Dataset {dataset_name} has no items")
                return None
                
            print(f"Successfully loaded {dataset_name} with {len(items)} examples")
            self.dataset_cache[cache_key] = dataset
            return dataset
            
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

    def run_optimization(self, dataset: Any, optimizer: Any, metrics: List[Any], initial_prompt: str, input_key: str, output_key: str, experiment_config: Dict) -> Dict:
        """Run optimization for a single dataset-optimizer pair."""
        if dataset is None or optimizer is None:
            print("Error: Dataset or optimizer is None")
            return None
        
        start_time = time.time()
        
        try:
            # Get dataset configuration
            dataset_name = experiment_config["dataset"]
            dataset_config = DATASET_CONFIGS[dataset_name]

            # Log dataset information
            print(f"\nDataset Information:")
            print(f"Name: {dataset_name}")
            print(f"Size: {len(dataset.get_items())} examples")
            print(f"Input key: {input_key}")
            print(f"Output key: {output_key}")
            print(f"Metrics: {[str(m) for m in metrics]}")

            # Log sample of original dataset items
            print("\nSample of original dataset items:")
            for i, item in enumerate(dataset.get_items()[:2]):  # Show first 2 items
                print(f"\nItem {i}:")
                for key, value in item.items():
                    print(f"  {key}: {value[:100]}..." if len(str(value)) > 100 else f"  {key}: {value}")

            # Format initial prompt based on optimizer type
            if isinstance(optimizer, FewShotBayesianOptimizer):
                # For FewShotBayesianOptimizer, format as chat prompt for evaluation
                formatted_initial_prompt = [{"role": "system", "content": initial_prompt}]
            else:
                formatted_initial_prompt = initial_prompt

            # Create optimization config with proper input mapping
            config = OptimizationConfig(
                dataset=dataset,
                objective=MetricConfig(
                    metric=metrics[0],
                    inputs={
                        "input": from_dataset_field(name=input_key),
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

            # Evaluate initial prompt
            start_time = time.time()
            initial_scores = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                future_to_metric = {}
                
                for metric in metrics:
                    # Create metric-specific config
                    # TODO: This is a hack to get the ContextPrecision metric to work, move this to the base class
                    if isinstance(metric, ContextPrecision):
                        metric_config = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key),
                                "output": from_llm_response_text(),
                                "reference": from_dataset_field(name=output_key),
                                "expected_output": from_dataset_field(name=output_key),  # Add expected_output for ContextPrecision
                                "context": from_dataset_field(name="context"),  # Add context field for ContextPrecision
                            }
                        )
                    else:
                        metric_config = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key),
                                "output": from_llm_response_text(),
                                "reference": from_dataset_field(name=output_key),
                            }
                        )
                    
                    # TODO: Update APIs to be consistent
                    if isinstance(optimizer, MetaPromptOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config,
                            task_config=config.task,
                            prompt=formatted_initial_prompt,
                            experiment_config=experiment_config,
                        )
                    elif isinstance(optimizer, MiproOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config,
                            prompt=formatted_initial_prompt,
                            experiment_config=experiment_config,
                        )
                    else:
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config,
                            prompt=formatted_initial_prompt,
                            experiment_config=experiment_config,
                        )
                    future_to_metric[future] = metric

                for future in as_completed(future_to_metric):
                    metric = future_to_metric[future]
                    try:
                        score = future.result()
                        if score is not None:
                            initial_scores[str(metric)] = score
                        else:
                            print(f"Warning: Got None score for {metric}")
                            initial_scores[str(metric)] = None
                    except Exception as e:
                        print(f"Error evaluating initial prompt for {metric}: {e}")
                        print(traceback.format_exc())
                        initial_scores[str(metric)] = None
                        continue

            # Run optimization
            try:
                if isinstance(optimizer, (MetaPromptOptimizer, MiproOptimizer)):
                    results = optimizer.optimize_prompt(config=config)
                elif isinstance(optimizer, FewShotBayesianOptimizer):
                    # Create a new dataset with processed items
                    try:
                        processed_dataset = get_or_create_dataset(
                            dataset_config["huggingface_path"],
                            test_mode=self.test_mode
                        )
                        
                        # Convert all items to have string values and proper structure
                        processed_items = []
                        for item in dataset.get_items():
                            # Create a new item with all string values
                            new_item = {
                                "id": str(item.get("id", "")),
                                "input": str(item.get(input_key, "")),
                                "output": str(item.get(output_key, "")),
                                "label": str(item.get("label", "")),  # Explicitly convert label to string
                            }
                            # Add any additional fields as strings
                            for key, value in item.items():
                                if key not in new_item:
                                    new_item[key] = str(value)
                            processed_items.append(new_item)
                        
                        # Set the processed items
                        processed_dataset.items = processed_items
                        
                        # Update the task config
                        processed_config = OptimizationConfig(
                            dataset=processed_dataset,
                            objective=config.objective,
                            task=PromptTaskConfig(
                                instruction_prompt=initial_prompt,
                                input_dataset_fields=[input_key],
                                output_dataset_field=output_key,
                                use_chat_prompt=True,
                            )
                        )
                        
                        # Run optimization with explicit string labels
                        results = optimizer.optimize_prompt(
                            config=processed_config,
                            n_trials=10
                        )
                    except Exception as e:
                        print(f"\nError creating processed dataset:")
                        print(f"Dataset name: {dataset_name}")
                        print(f"Number of items: {len(processed_items)}")
                        print(f"First item keys: {list(processed_items[0].keys()) if processed_items else 'No items'}")
                        print(f"Sample of processed items:")
                        for i, item in enumerate(processed_items[:2]):
                            print(f"\nItem {i}:")
                            for key, value in item.items():
                                print(f"  {key}: {value[:100]}..." if len(str(value)) > 100 else f"  {key}: {value}")
                        print(f"Error: {e}")
                        print(traceback.format_exc())
                        return None
                else:
                    raise ValueError(f"Unsupported optimizer type: {type(optimizer).__name__}")
            except Exception as e:
                print(f"\nError during optimization:")
                print(f"Dataset name: {dataset_name}")
                print(f"Optimizer type: {type(optimizer).__name__}")
                print(f"Error: {e}")
                print(traceback.format_exc())
                return None

            # Evaluate final prompt
            final_scores = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                future_to_metric = {}
                
                for metric in metrics:
                    metric_config = MetricConfig(
                        metric=metric,
                        inputs={
                            "input": from_dataset_field(name=input_key),
                            "output": from_llm_response_text(),
                            "reference": from_dataset_field(name=output_key),
                        }
                    )
                    
                    # Format final prompt based on optimizer type
                    final_prompt = results.prompt
                    if isinstance(optimizer, FewShotBayesianOptimizer):
                        # Format as chat prompt for evaluation
                        if isinstance(final_prompt, list):
                            final_prompt = final_prompt
                        else:
                            final_prompt = [{"role": "system", "content": final_prompt}]
                    
                    # TODO: Update APIs to be consistent
                    if isinstance(optimizer, MetaPromptOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config,
                            task_config=config.task,
                            prompt=final_prompt,
                            experiment_config=experiment_config,
                        )
                    elif isinstance(optimizer, MiproOptimizer):
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config,
                            prompt=final_prompt,
                            experiment_config=experiment_config,
                        )
                    else:
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config,
                            prompt=final_prompt,
                            experiment_config=experiment_config,
                        )
                    future_to_metric[future] = metric

                for future in as_completed(future_to_metric):
                    metric = future_to_metric[future]
                    try:
                        score = future.result()
                        if score is not None:
                            final_scores[str(metric)] = score
                        else:
                            print(f"Warning: Got None score for {metric}")
                            final_scores[str(metric)] = None
                    except Exception as e:
                        print(f"Error evaluating final prompt for {metric}: {e}")
                        print(traceback.format_exc())
                        final_scores[str(metric)] = None
                        continue

            end_time = time.time()
            
            # Get optimization history
            optimization_history = []
            if hasattr(results, "history"):
                for step in results.history:
                    history_entry = {
                        "step": getattr(step, "step", None),
                        "score": getattr(step, "score", None),
                        "prompt": getattr(step, "prompt", None),
                        "parameters": getattr(step, "parameters", None),
                        "timestamp": getattr(step, "timestamp", None)
                    }
                    optimization_history.append(history_entry)

            return {
                "initial_prompt": initial_prompt,
                "final_prompt": results.prompt,
                "initial_scores": initial_scores,
                "final_scores": final_scores,
                "time_taken": end_time - start_time,
                "dataset_size": len(dataset.get_items()),
                "experiment_config": experiment_config,
                "optimization_details": {
                    "num_iterations": getattr(results, "num_iterations", None),
                    "best_score": getattr(results, "best_score", None),
                    "optimization_history": optimization_history
                }
            }
        except Exception as e:
            print(f"\nUnexpected error during optimization:")
            print(f"Dataset name: {dataset_name}")
            print(f"Optimizer type: {type(optimizer).__name__}")
            print(f"Error: {e}")
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

        # Pre-cache datasets only once at the start
        self.pre_cache_datasets()

        # Verify datasets are loaded
        valid_datasets = []
        for dataset_key in datasets:
            dataset = self.dataset_cache.get(f"{dataset_key}_{self.test_mode}")
            if dataset is None:
                print(f"Skipping {dataset_key} - dataset not loaded")
                continue
            valid_datasets.append(dataset_key)

        if not valid_datasets:
            print("No valid datasets to run benchmarks on")
            return

        # Run optimizations in parallel
        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            futures = []
            
            for dataset_key in valid_datasets:
                dataset = self.dataset_cache[f"{dataset_key}_{self.test_mode}"]
                
                for optimizer_key in optimizers:
                    project_name = f"benchmark-{dataset_key}-{optimizer_key}-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
                    optimizer = self.create_optimizer(OPTIMIZER_CONFIGS[optimizer_key], project_name)
                    if optimizer is None:
                        print(f"Skipping {optimizer_key} - failed to create optimizer")
                        continue
                    
                    experiment_config = get_experiment_config(dataset_key, optimizer_key, test_mode=self.test_mode)
                    
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
            
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        test_mode_str = "_test" if self.test_mode else ""
        
        # Save as JSON
        json_path = self.output_dir / f"results{test_mode_str}_{timestamp}.json"
        with open(json_path, "w") as f:
            json.dump(self.results, f, indent=2)
        print(f"\nSaved detailed results to {json_path}")
        
        # Save as CSV
        df = pd.DataFrame(self.results)
        csv_path = self.output_dir / f"results{test_mode_str}_{timestamp}.csv"
        df.to_csv(csv_path, index=False)
        print(f"Saved results summary to {csv_path}")
        
        # Save checkpoint
        self.save_checkpoint()
        
        # Print current progress
        self.print_progress()
        
    def print_progress(self):
        """Print current progress of the benchmark."""
        if not self.results:
            print("No results yet")
            return
            
        df = pd.DataFrame(self.results)
        
        # Group by dataset and optimizer
        progress = df.groupby(["dataset", "optimizer"]).agg({
            "final_scores": lambda x: {str(k): f"{float(v):.4f}" if v is not None else "N/A" for k, v in x.iloc[-1].items()},
            "time_taken": "mean"
        }).round(3)
        
        print("\nCurrent Progress:")
        print(progress)

    def print_summary(self):
        """Print summary of results."""
        if not self.results:
            print("No results to summarize")
            return
            
        df = pd.DataFrame(self.results)
        
        # Calculate improvements
        for metric in df["initial_scores"].iloc[0].keys():
            df[f"{metric}_improvement"] = df.apply(
                lambda x: float(x["final_scores"][str(metric)]) - float(x["initial_scores"][str(metric)]) if str(metric) in x["final_scores"] and str(metric) in x["initial_scores"] else None,
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
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.monitor.generate_plots(timestamp)

def run_benchmark(
    runner: BenchmarkRunner,
    dataset_name: str,
    optimizer_name: str,
    test_mode: bool = False,
) -> None:
    """Run benchmark for a specific dataset and optimizer."""
    print(f"\nRunning benchmark for {dataset_name} with {optimizer_name}")
    print(f"Test mode: {test_mode}")
    
    # Get experiment configuration
    experiment_config = get_experiment_config(dataset_name, optimizer_name, test_mode)
    
    # Get dataset from cache
    dataset = runner.dataset_cache.get(f"{dataset_name}_{test_mode}")
    if dataset is None:
        print(f"Failed to load dataset {dataset_name}")
        return
        
    print(f"Using dataset with {len(dataset.get_items())} examples")
    
    # Create optimizer
    optimizer_config = OPTIMIZER_CONFIGS[optimizer_name]
    optimizer_class = getattr(
        opik_optimizer, optimizer_config["class"]
    )
    optimizer = optimizer_class(**optimizer_config["params"])
    
    # Get initial prompt
    initial_prompt = INITIAL_PROMPTS[dataset_name]
    
    # Run optimization
    result = runner.run_optimization(
        dataset=dataset,
        optimizer=optimizer,
        metrics=DATASET_CONFIGS[dataset_name]["metrics"],
        initial_prompt=initial_prompt,
        input_key=DATASET_CONFIGS[dataset_name]["input_key"],
        output_key=DATASET_CONFIGS[dataset_name]["output_key"],
        experiment_config=experiment_config,
    )
    
    if result:
        # Add dataset and optimizer info to result
        result["dataset"] = dataset_name
        result["optimizer"] = optimizer_name
        
        # Save results
        runner.results.append(result)
        runner.save_results()
        
        # Print summary
        runner.print_summary()
        
        # Print detailed results
        print("\nDetailed Results:")
        print(f"Initial Prompt: {result['initial_prompt']}")
        print(f"Final Prompt: {result['final_prompt']}")
        print("\nScores:")
        print("Initial:", {k: f"{v:.4f}" if v is not None else "None" for k, v in result['initial_scores'].items()})
        print("Final:", {k: f"{v:.4f}" if v is not None else "None" for k, v in result['final_scores'].items()})
        print(f"\nTime taken: {result['time_taken']:.2f} seconds")
        print(f"Dataset size: {result['dataset_size']}")
        if result['optimization_details']:
            print("\nOptimization Details:")
            print(f"Number of iterations: {result['optimization_details']['num_iterations']}")
            print(f"Best score: {result['optimization_details']['best_score']}")
    else:
        print("Optimization failed")

def main():
    """Main function to run benchmarks."""
    parser = argparse.ArgumentParser(description="Run benchmarks for prompt optimization")
    
    # Dataset arguments
    dataset_group = parser.add_mutually_exclusive_group()
    dataset_group.add_argument(
        "--dataset",
        type=str,
        default="gsm8k",
        help="Single dataset name to use for benchmarking (legacy support)",
    )
    dataset_group.add_argument(
        "--datasets",
        nargs="+",
        default=list(DATASET_CONFIGS.keys()),
        help="List of datasets to run benchmarks for",
    )
    
    # Optimizer arguments
    optimizer_group = parser.add_mutually_exclusive_group()
    optimizer_group.add_argument(
        "--optimizer",
        type=str,
        default="few_shot",
        help="Single optimizer name to use for benchmarking (legacy support)",
    )
    optimizer_group.add_argument(
        "--optimizers",
        nargs="+",
        default=list(OPTIMIZER_CONFIGS.keys()),
        help="List of optimizers to run benchmarks for",
    )
    
    # Other arguments
    parser.add_argument(
        "--output-dir",
        type=str,
        default="benchmark_results",
        help="Directory to save benchmark results",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=4,
        help="Maximum number of worker threads",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for reproducibility",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        help="Run in test mode with 5 examples per dataset",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume from latest checkpoint",
    )
    args = parser.parse_args()

    # Determine which datasets and optimizers to use
    datasets = [args.dataset] if args.dataset != "gsm8k" or not args.datasets else args.datasets
    optimizers = [args.optimizer] if args.optimizer != "few_shot" or not args.optimizers else args.optimizers

    # Validate datasets and optimizers
    invalid_datasets = [d for d in datasets if d not in DATASET_CONFIGS]
    if invalid_datasets:
        print(f"Error: Invalid datasets: {invalid_datasets}")
        print(f"Available datasets: {list(DATASET_CONFIGS.keys())}")
        return

    invalid_optimizers = [o for o in optimizers if o not in OPTIMIZER_CONFIGS]
    if invalid_optimizers:
        print(f"Error: Invalid optimizers: {invalid_optimizers}")
        print(f"Available optimizers: {list(OPTIMIZER_CONFIGS.keys())}")
        return

    print(f"\nRunning benchmarks for:")
    print(f"Datasets: {datasets}")
    print(f"Optimizers: {optimizers}")
    print(f"Test mode: {args.test_mode}")
    if args.resume:
        print("Resuming from latest checkpoint")
    
    # Create output directory
    output_path = Path(args.output_dir)
    output_path.mkdir(exist_ok=True)
    
    # Initialize runner once for all benchmarks
    runner = BenchmarkRunner(
        output_dir=args.output_dir,
        max_workers=args.max_workers,
        seed=args.seed,
        test_mode=args.test_mode,
    )
    
    # Pre-cache all datasets once at the start
    print("\nPre-caching all datasets...")
    runner.pre_cache_datasets()
    print("Dataset pre-caching complete\n")
    
    # Run benchmarks for each dataset-optimizer combination
    for dataset_name in datasets:
        for optimizer_name in optimizers:
            try:
                run_benchmark(
                    runner=runner,
                    dataset_name=dataset_name,
                    optimizer_name=optimizer_name,
                    test_mode=args.test_mode,
                )
            except Exception as e:
                print(f"Error running benchmark for {dataset_name} with {optimizer_name}: {e}")
                print(traceback.format_exc())
                continue
    
    # Print final summary
    if runner.results:
        print("\nFinal Benchmark Summary:")
        runner.print_summary()
        
        # Generate final plots
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        runner.monitor.generate_plots(timestamp)
    else:
        print("\nNo results to summarize")

if __name__ == "__main__":
    main() 