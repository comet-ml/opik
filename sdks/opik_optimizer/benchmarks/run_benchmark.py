import json
import time
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any, Optional, Tuple
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from functools import lru_cache
import argparse
import logging
import io # For SuppressOutput
import os # For SuppressOutput

import pandas as pd
# from datasets import load_dataset # No longer directly used here, handled by opik_optimizer.demo
# from tqdm import tqdm # Replaced with rich.progress

# Rich imports
from rich.progress import Progress, SpinnerColumn, BarColumn, TextColumn, TimeElapsedColumn, TimeRemainingColumn, ProgressColumn, TaskProgressColumn
from rich.panel import Panel
from rich.console import Console, Group
from rich.rule import Rule
from rich import print as rprint # Use rprint for direct rich printing (like tables)
from rich.table import Table
from rich.text import Text
from rich.console import Console
from rich.layout import Layout
from rich.spinner import Spinner
from rich.style import Style
from rich.live import Live
from rich.align import Align
from rich import box
from rich.status import Status # For dataset caching status
from rich.padding import Padding # For layout spacing

import opik_optimizer
from opik_optimizer import (
    FewShotBayesianOptimizer,
    MetaPromptOptimizer,
    MiproOptimizer,
    OptimizationConfig,
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.demo import get_or_create_dataset
from opik_optimizer.cache_config import initialize_cache, clear_cache
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision
from opik_optimizer.logging_config import setup_logging
from opik_optimizer import utils as opik_utils # Import the utils module
from opik_optimizer import optimization_result as opik_opt_result # Import opt result module
from opik.evaluation.metrics import BaseMetric # Added for custom JSON serializer

from benchmark_config import (
    DATASET_CONFIGS, 
    OPTIMIZER_CONFIGS, 
    INITIAL_PROMPTS,
    MODELS_TO_RUN, # Import MODELS_TO_RUN
    get_project_config,
    get_experiment_config,
)
from benchmark_monitor import get_optimization_monitor

# Initialize logger for this module
logger = logging.getLogger(__name__)
console = Console(
    width=120,  # Increased width slightly
    style=Style(color="white"),
    highlight=True,
    soft_wrap=True,
)

# Define consistent styles
STYLES = {
    "header": Style(color="cyan", bold=True),
    "success": Style(color="green", bold=True),
    "warning": Style(color="yellow", bold=True),
    "error": Style(color="red", bold=True),
    "info": Style(color="blue"),
    "dim": Style(dim=True),
}

# Configure progress bar columns
PROGRESS_COLUMNS = (
    SpinnerColumn(),
    TextColumn("[progress.description]{task.description}"),
    BarColumn(bar_width=40),
    TaskProgressColumn(),
    TextColumn("•"),
    TimeElapsedColumn(),
    TextColumn("•"),
    TimeRemainingColumn(),
)

# Custom JSON serializer to handle specific object types
def custom_json_serializer(obj: Any) -> Any:
    if isinstance(obj, BaseMetric):
        # For metric objects, return their string representation or a more detailed dict if available
        # For simplicity, str(obj) often gives a good summary (e.g., class name)
        return str(obj) 
    if hasattr(obj, 'isoformat'): # For datetime objects not handled by pydantic model_dump
        return obj.isoformat()
    if isinstance(obj, Path):
        return str(obj.resolve())
    # For any other types that json.dump can't handle by default
    # Consider adding more specific handlers if other unserializable types appear
    try:
        return str(obj) # Fallback to string representation for other unknown complex types
    except Exception:
        raise TypeError(f"Object of type {obj.__class__.__name__} is not JSON serializable and str() failed")

# Function to clean metric name representation
def clean_metric_name(metric_key_str: str) -> str:
    """Extracts class name like 'LevenshteinRatio' from string representations."""
    if isinstance(metric_key_str, str) and '<' in metric_key_str and 'object at' in metric_key_str:
         # Extract the class name part more robustly
         parts = metric_key_str.split('.')
         if len(parts) > 1:
             name = parts[-1]
             if ' object at' in name:
                 name = name.split(' object at')[0]
             return name
         else: # Fallback if format is unexpected
             return metric_key_str.strip('<> ') 
    return metric_key_str # Return as is if not matching the object format

class BenchmarkRunner:
    def __init__(self, output_dir: str = "benchmark_results", max_workers: int = 2, seed: int = 42, test_mode: bool = False, resume_enabled: bool = False, retry_failed_run_id: Optional[str] = None):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.results = []
        self.monitor = get_optimization_monitor(output_dir)
        self.max_workers = max_workers
        self.dataset_cache = {}
        self.seed = seed
        self.test_mode = test_mode
        self.resume_enabled = resume_enabled
        self.resuming_run_active = False
        self.retry_failed_run_id = retry_failed_run_id
        self.project_config = get_project_config(test_mode=test_mode)
        self.project_name = self.project_config["name"]
        self.workspace = self.project_config["workspace"]
        
        # Set global random seed
        import random
        import numpy as np
        random.seed(seed)
        np.random.seed(seed)
        logger.info(f"Global random seed set to [bold cyan]{seed}[/bold cyan]")
        
        # Initialize shared cache
        initialize_cache()
        logger.info("[green]Shared cache initialized.[/green]")
        
        # Create checkpoint directory
        self.checkpoint_dir = self.output_dir / "checkpoints"
        self.checkpoint_dir.mkdir(exist_ok=True)
        logger.info(f"Checkpoint directory created/ensured at [blue]{self.checkpoint_dir}[/blue]")
        
        # Load latest checkpoint if exists
        self.load_latest_checkpoint()

    def pre_cache_datasets(self):
        """Pre-cache all datasets if not already cached, with Rich Status and Console output."""
        console.print(Rule("Phase 1: Dataset Caching & Loading", style="dim blue"))
        datasets_processed = 0
        total_datasets = len(DATASET_CONFIGS)
        with Status("[yellow]Initializing dataset caching...[/yellow]", console=console, spinner="dots") as status:
            for dataset_key, dataset_config in DATASET_CONFIGS.items():
                datasets_processed += 1
                status_msg = f"[yellow]({datasets_processed}/{total_datasets}) Checking dataset: [bold]{dataset_config['name']}[/bold]...[/yellow]"
                status.update(status=status_msg)
                console.print(status_msg) # Print directly to console as well

                cache_key = f"{dataset_key}_{self.test_mode}"
                if cache_key not in self.dataset_cache:
                    console.print(f"  Attempting to load [bold]{dataset_config['name']}[/bold] (not cached)...")
                    try:
                        dataset = self.load_dataset(dataset_key, dataset_config["huggingface_path"])
                        if dataset is not None and dataset.get_items():
                            msg = f"[green]✓ Loaded {dataset_config['name']}[/green] ([cyan]{len(dataset.get_items())}[/cyan] items)"
                            logger.info(msg)
                            console.print(f"  {msg}")
                        else:
                            msg = f"[yellow]! Loaded {dataset_config['name']} but it has no items.[/yellow]"
                            logger.warning(msg)
                            console.print(f"  {msg}")
                    except Exception as e:
                        msg = f"[red]✗ Error loading {dataset_config['name']}: {e}[/red]"
                        logger.error(msg)
                        console.print(f"  {msg}")
                else:
                    items_count = len(self.dataset_cache[cache_key].get_items()) if self.dataset_cache[cache_key] else 0
                    msg = f"[green]✓ Using cached {dataset_config['name']}[/green] ([cyan]{items_count}[/cyan] items)"
                    logger.info(msg)
                    console.print(f"  {msg}") # Print cache status too
            
            final_status_msg = "[bold green]Dataset Caching & Loading Complete![/bold green]"
            status.update(status=final_status_msg)
        console.print(Rule(style="dim blue"))

    def load_latest_checkpoint(self):
        """Load the latest checkpoint if it exists, or a specific one if retry_failed_run_id is set."""
        checkpoint_files = []
        specific_checkpoint_to_load = None

        if self.retry_failed_run_id:
            logger.info(f"Attempting to load checkpoint for specific run_id to retry: [bold yellow]{self.retry_failed_run_id}[/bold yellow]")
            # Glob for checkpoints matching the specific run_id
            specific_run_checkpoints = list(self.checkpoint_dir.glob(f"checkpoint_{self.retry_failed_run_id}_*.json"))
            if not specific_run_checkpoints:
                logger.error(f"[red]No checkpoint found for specified retry_failed_run_id: {self.retry_failed_run_id}. Cannot retry.[/red]")
                # To prevent falling back to a generic latest checkpoint, we exit or signal error.
                # For now, treat as if no checkpoint found for resume purposes.
                self.results = []
                self.current_run_id = None # It was for a specific past run
                self.task_results_dir = None
                self.resuming_run_active = False
                self.retry_failed_run_id = None # Clear it as we can't proceed with retry
                return
            specific_checkpoint_to_load = max(specific_run_checkpoints, key=lambda x: x.stat().st_mtime)
            logger.info(f"Found specific checkpoint to retry: [blue]{specific_checkpoint_to_load}[/blue]")
        elif self.resume_enabled:
            logger.info(f"Resume enabled. Looking for the latest overall checkpoint.")
            all_checkpoints = list(self.checkpoint_dir.glob("checkpoint_*.json"))
            if all_checkpoints:
                specific_checkpoint_to_load = max(all_checkpoints, key=lambda x: x.stat().st_mtime)
                logger.info(f"Found latest overall checkpoint to resume: [blue]{specific_checkpoint_to_load}[/blue]")
        
        if not specific_checkpoint_to_load:
            logger.info("No checkpoint to load (either not resuming, not retrying, or none found). Starting a fresh run state.")
            self.results = []
            self.current_run_id = None 
            self.task_results_dir = None
            self.resuming_run_active = False
            return

        logger.info(f"Loading checkpoint from: [blue]{specific_checkpoint_to_load}[/blue]")
        
        try:
            with open(specific_checkpoint_to_load, "r") as f:
                checkpoint_data = json.load(f)
        except Exception as e:
            logger.error(f"[red]Error loading checkpoint file {specific_checkpoint_to_load}: {e}. Starting a fresh run.[/red]")
            self.results = []
            self.current_run_id = None 
            self.task_results_dir = None
            self.resuming_run_active = False # Not resuming if checkpoint load fails
            return

        # Restore state from checkpoint
        self.current_run_id = checkpoint_data.get("run_id")
        task_results_dir_str = checkpoint_data.get("task_results_dir")
        if task_results_dir_str:
            self.task_results_dir = Path(task_results_dir_str)
            logger.info(f"Restored task_results_dir: {self.task_results_dir}")
        else:
            # If task_results_dir is not in checkpoint, try to infer or warn
            if self.current_run_id:
                self.task_results_dir = self.output_dir / "task_results" / self.current_run_id
                logger.warning(f"[yellow]task_results_dir not found in checkpoint, inferred to: {self.task_results_dir}[/yellow]")
                self.task_results_dir.mkdir(parents=True, exist_ok=True) # Ensure it exists
            else:
                self.task_results_dir = None
                logger.warning("[yellow]run_id and task_results_dir not found in checkpoint. Resuming might be problematic.[/yellow]")

        self.results = checkpoint_data.get("results_summary", [])
        self.test_mode = checkpoint_data.get("test_mode", self.test_mode) # Keep self.test_mode if not in checkpoint
        self.project_config = checkpoint_data.get("project_config", self.project_config)
        if self.project_config:
            self.project_name = self.project_config.get("name", self.project_name)
            self.workspace = self.project_config.get("workspace", self.workspace)

        # Restore monitor state
        monitor_state = checkpoint_data.get("monitor_state", {})
        if hasattr(self.monitor, 'metrics_history'):
            self.monitor.metrics_history = monitor_state.get("metrics_history", [])
        if hasattr(self.monitor, 'prompts_history'):
            self.monitor.prompts_history = monitor_state.get("prompts_history", [])

        # Restore dataset cache keys and reload datasets if necessary
        # This part remains largely the same, ensuring datasets are available
        dataset_cache_keys = checkpoint_data.get("dataset_cache_keys", [])
        if dataset_cache_keys:
            logger.info("Restoring dataset cache...")
            for key in dataset_cache_keys:
                if key not in self.dataset_cache:
                    # Attempt to parse dataset_name and test_mode_suffix from key
                    parts = key.rsplit('_', 1)
                    dataset_name_from_key = parts[0]
                    # test_mode_suffix = parts[1] if len(parts) > 1 else None 
                    # We rely on self.test_mode which should be correctly set from checkpoint or args

                    if dataset_name_from_key in DATASET_CONFIGS:
                        logger.info(f"  Re-caching dataset for key: {key} (name: {dataset_name_from_key})")
                        self.load_dataset(dataset_name_from_key, DATASET_CONFIGS[dataset_name_from_key]["huggingface_path"])
                    else:
                        logger.warning(f"  [yellow]Dataset config for '{dataset_name_from_key}' (from cache key '{key}') not found. Cannot re-cache.[/yellow]")
        
        # Restore environment details from checkpoint if needed (e.g., seed, max_workers)
        # These are typically set by args, but checkpoint can provide context of the saved run.
        env_details = checkpoint_data.get("environment", {})
        # self.seed = env_details.get("seed", self.seed) # Args should override for a new run, but good for info
        # self.max_workers = env_details.get("max_workers", self.max_workers)
        logger.info(f"Checkpoint loaded. Run ID: [bold yellow]{self.current_run_id}[/bold yellow]")
        logger.info(f"  Restored [cyan]{len(self.results)}[/cyan] task summaries from checkpoint.")
        logger.info(f"  Restored [cyan]{len(self.monitor.metrics_history)}[/cyan] metrics history entries.")
        logger.info(f"  Restored [cyan]{len(self.monitor.prompts_history)}[/cyan] prompts history entries.")
        logger.info(f"  Restored [cyan]{len(dataset_cache_keys)}[/cyan] dataset cache keys.")
        logger.info(f"  Original run seed: {env_details.get('seed')}, max_workers: {env_details.get('max_workers')}")
        
        if self.resume_enabled or self.retry_failed_run_id: # Activate resume if EITHER normal resume or retrying a specific run
            self.resuming_run_active = True
            if self.retry_failed_run_id:
                # When retrying, the current_run_id of the BenchmarkRunner instance should be the one we are retrying.
                # The new results will be saved under a *new* run_id generated by the current execution.
                # So, we load the old run_id here to correctly identify tasks from it, but the new run will have its own ID.
                # self.current_run_id = self.retry_failed_run_id # This sets the context for which tasks to look for.
                # Actually, self.current_run_id from checkpoint_data.get("run_id") is what we need for self.results filtering.
                # And the new run will generate its own self.current_run_id when run_benchmark starts.
                # Let's ensure the loaded checkpoint_data["run_id"] is what we operate on for task identification.
                run_id_from_checkpoint = checkpoint_data.get("run_id")
                logger.info(f"[green]Checkpoint for run_id '{run_id_from_checkpoint}' loaded successfully for retrying failed tasks.[/green]")
                # The self.current_run_id for THIS execution of BenchmarkRunner will be new, generated in run_benchmark.
                # self.task_results_dir for THIS execution will also be new.
                # We are essentially using the *loaded* self.results from the *old* run_id to filter tasks.
            elif self.resume_enabled:
                 current_run_id_for_log = self.current_run_id # self.current_run_id is set from checkpoint here
                 logger.info(f"[green]Checkpoint loaded successfully and resume mode is active for run_id '{current_run_id_for_log}'.[/green]")
        else:
            self.resuming_run_active = False
            logger.info("Resume mode not enabled. Checkpoint data loaded, but all tasks will be scheduled if not already present in a *new* run.")

    def save_checkpoint(self):
        """Save current state to a checkpoint file."""
        if not hasattr(self, 'current_run_id') or not self.current_run_id:
            # If run_id is not set (e.g. benchmark hasn't started full run process),
            # use a generic timestamp for checkpoint name, or don't save.
            # For now, let's prevent saving if current_run_id isn't there, as it implies an unstable state.
            logger.warning("[yellow]Cannot save checkpoint: current_run_id is not set. Benchmark might not have started properly.[/yellow]")
            return

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        # Checkpoint file name now includes the run_id for better organization if multiple runs use same output_dir for checkpoints
        checkpoint_file = self.checkpoint_dir / f"checkpoint_{self.current_run_id}_{timestamp}.json"
        
        # self.results now contains task metadata (summaries), not full deep results.
        # The full results are in individual JSON files per task.
        # So, no complex serialization of optimization_history is needed here.

        checkpoint_data = {
            "checkpoint_timestamp": timestamp,
            "run_id": self.current_run_id,
            "task_results_dir": str(self.task_results_dir.resolve()) if hasattr(self, 'task_results_dir') and self.task_results_dir else None,
            "test_mode": self.test_mode,
            "project_config": self.project_config, # Still useful to save the overall project config used
            "results_summary": self.results, # List of task metadata dicts
            "dataset_cache_keys": list(self.dataset_cache.keys()),
            "monitor_state": {
                "metrics_history": self.monitor.metrics_history if hasattr(self.monitor, 'metrics_history') else [],
                "prompts_history": self.monitor.prompts_history if hasattr(self.monitor, 'prompts_history') else []
            },
            "environment": {
                "python_version": sys.version,
                "opik_optimizer_version": opik_optimizer.__version__, # Added opik_optimizer version
                "seed": self.seed,
                "max_workers": self.max_workers
            }
        }
        
        try:
            with open(checkpoint_file, "w") as f:
                json.dump(checkpoint_data, f, indent=2)
            logger.info(f"Saved checkpoint to [blue]{checkpoint_file}[/blue]")
        except Exception as e:
            logger.error(f"[red]Failed to save checkpoint to {checkpoint_file}: {e}[/red]")
            logger.exception("Traceback for saving checkpoint:")

    def load_dataset(self, dataset_name: str, huggingface_path: str) -> Any:
        """Load dataset from HuggingFace or create if not exists, passing the seed."""
        cache_key = f"{dataset_name}_{self.test_mode}"
        if cache_key in self.dataset_cache:
            return self.dataset_cache[cache_key]
            
        try:
            logger.info(f"\nLoading dataset [bold]{dataset_name}[/bold] from [blue]{huggingface_path}[/blue] (Test Mode: {self.test_mode}, Seed: {self.seed})...")
            # Pass the runner's seed to the dataset creation/loading function
            dataset = get_or_create_dataset(
                huggingface_path, 
                test_mode=self.test_mode, 
                seed=self.seed # Pass the seed here
            )
            
            if dataset is None:
                logger.warning(f"[yellow]Failed to load dataset {dataset_name}[/yellow]")
                return None
                
            items = dataset.get_items()
            if not items:
                logger.warning(f"[yellow]Warning: Dataset {dataset_name} loaded but has no items[/yellow]")
                # Still cache the empty dataset object to avoid reloading attempts
                self.dataset_cache[cache_key] = dataset
                return dataset # Return the empty dataset
                
            logger.info(f"[green]Successfully loaded {dataset_name}[/green] with [cyan]{len(items)}[/cyan] examples")
            self.dataset_cache[cache_key] = dataset
            return dataset
            
        except Exception as e:
            logger.error(f"[red]Error loading dataset {dataset_name}: {e}[/red]")
            logger.exception(f"Traceback for error loading dataset [bold]{dataset_name}[/bold]:")
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
            logger.error(f"[red]Error creating optimizer {optimizer_config['class']}: {e}[/red]")
            logger.exception(f"Traceback for error creating optimizer [bold]{optimizer_config['class']}[/bold]:")
            return None

    def run_optimization(self, dataset: Any, optimizer: Any, metrics: List[Any], initial_prompt: str, input_key: str, output_key: str, experiment_config: Dict) -> Dict:
        if dataset is None or optimizer is None:
            logger.error("[bold red]Error: Dataset or optimizer is None[/bold red]")
            return None
        
        start_time_run_opt = time.time()
        run_id = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{os.urandom(4).hex()}" # Unique ID for the overall benchmark execution
        task_id = f"{experiment_config['dataset']}-{experiment_config['optimizer']}-{optimizer.model if hasattr(optimizer, 'model') else 'unknown_model'}-{datetime.now().strftime('%Y%m%d%H%M%S')}"

        dataset_name = experiment_config["dataset"]
        optimizer_name = type(optimizer).__name__
        console.print(f"🏁 Starting task: [bold magenta]{task_id}[/bold magenta] ({dataset_name} / {optimizer_name} / {optimizer.model})")
        logger.info(f"Starting opt task_id: {task_id}...")

        # Initialize comprehensive result structure
        task_result = {
            "run_id": run_id, # This should ideally be passed in or set at a higher level for a single benchmark run
            "task_id": task_id,
            "timestamp_start_task": datetime.now().isoformat(),
            "timestamp_end_task": None,
            "duration_seconds_task": None,
            "status": "failure", # Default to failure, update on success
            "error_message": None,
            "test_mode": self.test_mode,
            "seed": self.seed,
            "max_workers": self.max_workers,
            "project_name_opik": getattr(optimizer, 'project_name', None), # Assuming optimizer has project_name
            "environment": {
                "python_version": sys.version,
                "opik_optimizer_version": opik_optimizer.__version__,
                # TODO: Add other key library versions if needed
            },
            "config": {
                "dataset_config_name": dataset_name,
                "dataset_huggingface_path": DATASET_CONFIGS[dataset_name]["huggingface_path"],
                "optimizer_config_name": experiment_config["optimizer"],
                "optimizer_class": optimizer_name,
                "model_name": optimizer.model, # Store the actual model name used
                "optimizer_params": OPTIMIZER_CONFIGS[experiment_config["optimizer"]]["params"],
                "initial_prompt": initial_prompt,
                "input_key": input_key,
                "output_key": output_key,
            },
            "initial_evaluation": None,
            "optimization_process": None,
            "final_evaluation": None,
            "raw_optimizer_result": None, # For storing the direct output of optimize_prompt
        }

        # List to collect errors during evaluation steps
        evaluation_errors = []

        try:
            if isinstance(optimizer, FewShotBayesianOptimizer):
                formatted_initial_prompt = [{"role": "system", "content": initial_prompt}]
                logger.info("Chat format for initial eval.")
            else:
                formatted_initial_prompt = initial_prompt
                logger.info("String format for initial eval.")

            dataset_config = DATASET_CONFIGS[dataset_name]
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
                task=TaskConfig(
                    instruction_prompt=initial_prompt,
                    input_dataset_fields=[input_key],
                    output_dataset_field=output_key,
                    use_chat_prompt=isinstance(optimizer, FewShotBayesianOptimizer),
                )
            )

            # --- Initial Prompt Evaluation --- 
            logger.info("--> Evaluating initial prompt...")
            start_time_eval_initial = time.time()
            initial_scores = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                future_to_metric = {}
                for metric in metrics:
                    # Create metric-specific config - Handles ContextPrecision
                    if isinstance(metric, ContextPrecision):
                        metric_config_eval = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key), # Often 'question'
                                "output": from_llm_response_text(), # LLM response
                                "expected_output": from_dataset_field(name="answer"), # Map to 'answer' field
                                "context": from_dataset_field(name="context") # Context field
                            },
                            # No scoring_key_mapping needed if inputs match metric.score args
                        )
                        logger.debug(f"Using ContextPrecision config mapping expected_output->answer for initial eval")
                    else: # Default config for other metrics
                        metric_config_eval = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key),
                                "output": from_llm_response_text(),
                                "reference": from_dataset_field(name=output_key), # Usually 'answer' or similar
                            }
                        )
                        logger.debug(f"Using default metric config for {metric} for initial eval")
                    
                    prompt_for_eval = formatted_initial_prompt
                    # HACK: Add dummy user message for Anthropic if prompt is only a system message
                    model_is_anthropic = "anthropic" in optimizer.model.lower()
                    if model_is_anthropic and isinstance(prompt_for_eval, list) and len(prompt_for_eval) == 1 and prompt_for_eval[0].get("role") == "system":
                        prompt_for_eval = prompt_for_eval + [{"role": "user", "content": "(Proceed based on system instructions)"}]
                        logger.warning(f"Applied Anthropic eval hack: Added dummy user message to initial system prompt.")

                    if isinstance(optimizer, MetaPromptOptimizer):
                        future = executor.submit(optimizer.evaluate_prompt, dataset=dataset, metric_config=metric_config_eval, task_config=config.task, prompt=prompt_for_eval, experiment_config=experiment_config)
                    else:
                        future = executor.submit(optimizer.evaluate_prompt, dataset=dataset, metric_config=metric_config_eval, prompt=prompt_for_eval, experiment_config=experiment_config)
                    future_to_metric[future] = metric

                for future in as_completed(future_to_metric):
                    metric_obj = future_to_metric[future]
                    try:
                        initial_scores[str(metric_obj)] = future.result()
                    except Exception as e:
                        initial_scores[str(metric_obj)] = None
                        err_msg = f"Initial eval err ({metric_obj}): {e}"
                        logger.error(f"[red]{err_msg}[/red]")
                        evaluation_errors.append(err_msg)
            
            initial_eval_time = time.time() - start_time_eval_initial
            scores_str = ", ".join([f"{k}: {v:.4f}" if isinstance(v, (int, float)) else f"{k}: N/A" for k, v in initial_scores.items()])
            logger.info(f"  Initial eval ({task_id}): {scores_str} ({initial_eval_time:.2f}s)")
            
            # Store initial evaluation results properly
            task_result["initial_evaluation"] = {
                "metrics": [
                    {
                        "metric_name": str(metric),
                        "score": score,
                        "timestamp": datetime.now().isoformat()
                    }
                    for metric, score in initial_scores.items()
                ],
                "duration_seconds": initial_eval_time,
                "prompt_used": formatted_initial_prompt
            }

            # --- Run Optimization --- 
            n_trials_log = getattr(optimizer, 'n_trials', 'N/A')
            logger.info(f"--> Running optimization ({n_trials_log} trials)...")
            start_time_opt = time.time()
            results_obj = None
            raw_results_obj_for_saving = None # To store the direct output

            try:
                if isinstance(optimizer, (MetaPromptOptimizer, MiproOptimizer)):
                    results_obj = optimizer.optimize_prompt(config=config)
                elif isinstance(optimizer, FewShotBayesianOptimizer):
                    processed_dataset = get_or_create_dataset(dataset_config["huggingface_path"], test_mode=self.test_mode)
                    processed_items = []
                    for item in dataset.get_items():
                        new_item = { "id": str(item.get("id", "")), "input": str(item.get(input_key, "")), "output": str(item.get(output_key, "")), "label": str(item.get("label", ""))}
                        for k, v in item.items():
                            if k not in new_item: new_item[k] = str(v)
                        processed_items.append(new_item)
                    processed_dataset.items = processed_items
                    num_processed_items = len(processed_items)
                    if num_processed_items == 0: raise ValueError("Processed dataset is empty after processing.")
                    o_min, o_max = getattr(optimizer, "min_examples", 1), getattr(optimizer, "max_examples", 1)
                    optimizer.max_examples = max(1, min(o_max, num_processed_items))
                    optimizer.min_examples = max(1, min(o_min, optimizer.max_examples))
                    if num_processed_items == 1: optimizer.min_examples, optimizer.max_examples = 1, 1
                    cur_met_conf = config.objective
                    cur_task_conf = TaskConfig(instruction_prompt=initial_prompt, input_dataset_fields=[input_key], output_dataset_field=output_key, use_chat_prompt=True)
                    results_obj = optimizer.optimize_prompt(
                        dataset=processed_dataset,
                        metric_config=cur_met_conf, 
                        task_config=cur_task_conf, 
                        n_trials=getattr(optimizer, 'n_trials', 10),
                        n_samples=getattr(optimizer, 'n_samples', 100)
                    )
                else:
                    logger.error(f"Unsupported optimizer: {optimizer_name}")
                    task_result["error_message"] = f"Unsupported optimizer: {optimizer_name}"
                    task_result["timestamp_end_task"] = datetime.now().isoformat()
                    task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                    return task_result
                
                # Store the raw result object before any processing, if serializable or convertible
                if results_obj:
                    try:
                        # Attempt to convert to dict if it's a Pydantic model or similar
                        if hasattr(results_obj, 'model_dump') and callable(results_obj.model_dump):
                            try:
                                raw_results_obj_for_saving = results_obj.model_dump()
                            except TypeError as e_dump_no_args:
                                logger.warning(f"results_obj.model_dump() failed: {e_dump_no_args}. Trying with exclude_none=True.")
                                try:
                                    raw_results_obj_for_saving = results_obj.model_dump(exclude_none=True)
                                except TypeError as e_dump_exclude:
                                    logger.warning(f"results_obj.model_dump(exclude_none=True) also failed: {e_dump_exclude}. Falling back to .dict().")
                                    if hasattr(results_obj, 'dict') and callable(results_obj.dict):
                                        raw_results_obj_for_saving = results_obj.dict()
                                    elif hasattr(results_obj, '__dict__'):
                                        raw_results_obj_for_saving = vars(results_obj)
                                    else:
                                        raw_results_obj_for_saving = str(results_obj)
                        elif hasattr(results_obj, 'dict') and callable(results_obj.dict): 
                            raw_results_obj_for_saving = results_obj.dict()
                        elif hasattr(results_obj, '__dict__'):
                            raw_results_obj_for_saving = vars(results_obj)
                        else:
                            raw_results_obj_for_saving = str(results_obj) # Fallback to string
                    except Exception as e_ser:
                        logger.warning(f"Could not serialize raw results_obj: {e_ser}")
                task_result["raw_optimizer_result"] = raw_results_obj_for_saving

            except Exception as e:
                logger.error(f"[red]Error during {optimizer_name}.optimize_prompt: {e}[/red]")
                logger.exception("Traceback:")
                task_result["error_message"] = f"Error in {optimizer_name}.optimize_prompt: {str(e)}"
                task_result["timestamp_end_task"] = datetime.now().isoformat()
                task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                return task_result

            opt_time = time.time() - start_time_opt
            if results_obj is None:
                logger.error(f"[bold red]Optimization failed for {optimizer_name}. results_obj is None.[/bold red]")
                task_result["error_message"] = f"Optimization failed for {optimizer_name}, results_obj is None."
                task_result["timestamp_end_task"] = datetime.now().isoformat()
                task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                return task_result
                
            num_iter_log = getattr(results_obj, "num_iterations", len(getattr(results_obj, "history", [])))
            best_score_log = getattr(results_obj, "best_score", getattr(results_obj, "score", "N/A"))
            best_score_log_str = f"{best_score_log:.4f}" if isinstance(best_score_log, (int,float)) else str(best_score_log)
            console.print(f"  Optimization done ({task_id}): Iterations={num_iter_log}, Best Internal Score={best_score_log_str} ({opt_time:.2f}s)")

            # Process optimization history for structured logging
            opt_history_processed = []
            raw_history = []
            if hasattr(results_obj, "history") and results_obj.history and isinstance(results_obj.history, list):
                raw_history = results_obj.history
            elif isinstance(results_obj, dict) and "history" in results_obj and isinstance(results_obj["history"], list):
                raw_history = results_obj["history"]
            
            for i, hist_item in enumerate(raw_history):
                # hist_item could be a dict, an object, or an OptimizationResult object from opik_optimizer
                iter_detail = {
                    "iteration": i + 1,
                    "timestamp": getattr(hist_item, "timestamp", datetime.now().isoformat()), # TODO: ensure timestamp is from actual event
                    "prompt_candidate": getattr(hist_item, "prompt", None),
                    "parameters_used": getattr(hist_item, "parameters", None), # e.g. for FewShotBayesianOptimizer
                    "scores": [], # list of {metric_name, score, opik_eval_id, ...}
                    "tokens_used": None, # TODO: Populate tokens
                    "cost": None, # TODO: Populate cost
                    "duration_seconds": None, # TODO: Capture duration of this specific iteration if possible
                }
                current_score_val = None
                if isinstance(hist_item, dict):
                    current_score_val = hist_item.get('score', hist_item.get('current_score'))
                    # If scores are nested, extract them
                    # This part is a guess and needs validation based on actual hist_item structure
                    nested_scores = hist_item.get("scores_per_metric", hist_item.get("metric_scores"))
                    if isinstance(nested_scores, dict):
                        for m_name, m_score in nested_scores.items():
                            iter_detail["scores"].append({"metric_name": m_name, "score": m_score, "opik_evaluation_id": None})
                    elif current_score_val is not None:
                         iter_detail["scores"].append({"metric_name": "objective_score", "score": current_score_val, "opik_evaluation_id": None})
                elif hasattr(hist_item, 'score'): # For Pydantic models or simple objects
                    current_score_val = hist_item.score
                    # Similar logic for nested scores if applicable for these objects
                    if hasattr(hist_item, 'scores_per_metric') and isinstance(hist_item.scores_per_metric, dict):
                        for m_name, m_score in hist_item.scores_per_metric.items():
                            iter_detail["scores"].append({"metric_name": m_name, "score": m_score, "opik_evaluation_id": None})
                    elif current_score_val is not None:
                        iter_detail["scores"].append({"metric_name": "objective_score", "score": current_score_val, "opik_evaluation_id": None})
                
                if not iter_detail["scores"] and current_score_val is not None: # Fallback if no detailed scores found but a general score exists
                     iter_detail["scores"].append({"metric_name": "objective_score", "score": current_score_val, "opik_evaluation_id": None})
                
                opt_history_processed.append(iter_detail)

            task_result["optimization_process"] = {
                "timestamp_start": datetime.fromtimestamp(start_time_opt).isoformat(),
                "timestamp_end": datetime.now().isoformat(), # End of optimization block
                "duration_seconds": opt_time,
                "optimizer_type": optimizer_name,
                # Fetch configured trials/samples from optimizer instance attributes
                "num_trials_configured": getattr(optimizer, 'n_trials', getattr(optimizer, 'n_iterations', None)), # Use n_iterations as fallback for FewShot
                "num_samples_configured": getattr(optimizer, 'n_samples', None),
                "best_score_achieved": getattr(results_obj, 'score', None), 
                "final_prompt": results_obj.details.get("chat_messages") if optimizer_name == "FewShotBayesianOptimizer" and hasattr(results_obj, 'details') else getattr(results_obj, 'prompt', None), 
                "history": opt_history_processed,
            }

            # --- Final Prompt Evaluation --- 
            logger.info("--> Evaluating final prompt...")
            final_scores = {}
            start_time_eval_final = time.time()
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                future_to_metric_final = {}
                
                # Determine the correct prompt format for final evaluation
                final_prompt_to_eval = None
                if optimizer_name == "FewShotBayesianOptimizer" and hasattr(results_obj, 'details'):
                    final_prompt_to_eval = results_obj.details.get("chat_messages")
                    if not final_prompt_to_eval: # Fallback if chat_messages isn't there for some reason
                         final_prompt_to_eval = results_obj.details.get("prompt_parameter").as_template().format() # Reconstruct
                    logger.info(f"Using FewShot chat messages for final eval.")
                else:
                    final_prompt_to_eval = getattr(results_obj, 'prompt', None) # Use base prompt for others
                    if isinstance(final_prompt_to_eval, str): # Ensure it's in list format if needed
                        final_prompt_to_eval = [{"role": "system", "content": final_prompt_to_eval}]
                        logger.info(f"Using string prompt (wrapped as system) for final eval.")
                    
                if final_prompt_to_eval is None:
                    logger.error("[red]No final prompt structure found for evaluation.[/red]")
                else:
                    # Apply Anthropic hack if necessary (check if final_prompt_to_eval is a list)
                    prompt_for_final_eval = final_prompt_to_eval
                    model_is_anthropic = "anthropic" in optimizer.model.lower()
                    if model_is_anthropic and isinstance(prompt_for_final_eval, list) and len(prompt_for_final_eval) == 1 and prompt_for_final_eval[0].get("role") == "system":
                        prompt_for_final_eval = prompt_for_final_eval + [{"role": "user", "content": "(Proceed based on system instructions)"}]
                        logger.warning(f"Applied Anthropic eval hack: Added dummy user message to final system prompt.")
                    elif model_is_anthropic and isinstance(prompt_for_final_eval, list) and len(prompt_for_final_eval) > 1 and prompt_for_final_eval[-1].get("role") == "assistant":
                         # If the last message is assistant (common in few-shot), add a dummy user msg
                         prompt_for_final_eval = prompt_for_final_eval + [{"role": "user", "content": "(Proceed based on provided examples and system instructions)"}]
                         logger.warning(f"Applied Anthropic eval hack: Added dummy user message after final assistant message in few-shot prompt.")


                    for metric_final_obj in metrics:
                        # Create metric-specific config for final eval - Handles ContextPrecision
                        if isinstance(metric_final_obj, ContextPrecision):
                            metric_config_final_eval = MetricConfig(
                                metric=metric_final_obj,
                                inputs={
                                    "input": from_dataset_field(name=input_key),
                                    "output": from_llm_response_text(),
                                    "expected_output": from_dataset_field(name="answer"), # Map to 'answer' field
                                    "context": from_dataset_field(name="context")
                                }
                            )
                            logger.debug(f"Using ContextPrecision config mapping expected_output->answer for final eval")
                        else: # Default config
                            metric_config_final_eval = MetricConfig(
                                metric=metric_final_obj,
                                inputs={
                                    "input": from_dataset_field(name=input_key),
                                    "output": from_llm_response_text(),
                                    "reference": from_dataset_field(name=output_key),
                                }
                            )
                            logger.debug(f"Using default metric config for {metric_final_obj} for final eval")

                        # Submit final evaluation job using the correctly formatted prompt_for_final_eval
                        if isinstance(optimizer, MetaPromptOptimizer):
                             # MetaPromptOptimizer needs task_config passed to evaluate_prompt
                            future = executor.submit(optimizer.evaluate_prompt, dataset=dataset, metric_config=metric_config_final_eval, task_config=config.task, prompt=prompt_for_final_eval, experiment_config=experiment_config)
                        else:
                            # Other optimizers (like FewShot) take prompt directly
                            future = executor.submit(optimizer.evaluate_prompt, dataset=dataset, metric_config=metric_config_final_eval, prompt=prompt_for_final_eval, experiment_config=experiment_config)
                        future_to_metric_final[future] = metric_final_obj
                        
                for future in as_completed(future_to_metric_final):
                    metric_obj_final = future_to_metric_final[future]
                    try:
                        final_scores[str(metric_obj_final)] = future.result()
                    except Exception as e:
                        final_scores[str(metric_obj_final)] = None
                        err_msg = f"Final eval err ({metric_obj_final}): {e}"
                        logger.error(f"[red]{err_msg}[/red]")
                        evaluation_errors.append(err_msg)
            
            final_eval_time = time.time() - start_time_eval_final
            final_scores_str = ", ".join([f"{k}: {v:.4f}" if isinstance(v, (int, float)) else f"{k}: N/A" for k, v in final_scores.items()])
            logger.info(f"  Final eval ({task_id}): {final_scores_str} ({final_eval_time:.2f}s)")

            # Store final evaluation results properly
            task_result["final_evaluation"] = {
                "metrics": [
                    {
                        "metric_name": str(metric),
                        "score": score,
                        "timestamp": datetime.now().isoformat()
                    }
                    for metric, score in final_scores.items()
                ],
                "duration_seconds": final_eval_time,
                "prompt_used": prompt_for_final_eval # Log the actual prompt used
            }

            # Store the final prompt representation in the top-level results (this is somewhat redundant now)
            # Let's simplify and rely on the prompt_used in final_evaluation and the final_prompt in optimization_process
            task_result["final_prompt"] = task_result["optimization_process"]["final_prompt"] # Keep top-level consistent with opt_process

            # Check for evaluation errors before setting status to success
            if evaluation_errors:
                 task_result["error_message"] = "; ".join(evaluation_errors)
                 task_result["status"] = "failure_in_evaluation" # More specific status
            else:
                 task_result["status"] = "success"

            # --- Package Results --- 
            total_run_time_task = time.time() - start_time_run_opt
            task_result["timestamp_end_task"] = datetime.now().isoformat()
            task_result["duration_seconds_task"] = total_run_time_task
            if task_result["status"] == "success":
                console.print(f"[green]✓ Completed task: [bold magenta]{task_id}[/bold magenta] in {total_run_time_task:.2f}s[/green]")
            else:
                console.print(f"[red]✗ Failed task: [bold magenta]{task_id}[/bold magenta] in {total_run_time_task:.2f}s (Status: {task_result['status']})[/red]")
            return task_result
        
        except Exception as e:
            logger.error(f"[red]Unexpected error in run_opt for {dataset_name}/{optimizer_name} (task: {task_id}): {e}[/red]")
            logger.exception("Traceback:")
            task_result["error_message"] = f"Outer exception in run_optimization: {str(e)} - Traceback: {traceback.format_exc()}"
            task_result["status"] = "failure"
            task_result["timestamp_end_task"] = datetime.now().isoformat()
            task_result["duration_seconds_task"] = time.time() - start_time_run_opt # Ensure this is set even on outer error
            return task_result

    def run_benchmark(self, datasets: List[str] = None, optimizers: List[str] = None):
        """Run benchmark with Live display showing overall progress and active tasks."""
        overall_start_time = time.time()
        if datasets is None: datasets = list(DATASET_CONFIGS.keys())
        if optimizers is None: optimizers = list(OPTIMIZER_CONFIGS.keys())
        print_benchmark_header(datasets, optimizers, self.test_mode)
        self.pre_cache_datasets()
        active_datasets_to_run = []
        for dataset_key in datasets:
            cache_key = f"{dataset_key}_{self.test_mode}"
            if cache_key in self.dataset_cache and self.dataset_cache[cache_key] and self.dataset_cache[cache_key].get_items():
                active_datasets_to_run.append(dataset_key)
            else:
                logger.warning(f"[yellow]Dataset [bold]{dataset_key}[/bold] was not loaded/empty. Skipping.[/yellow]")

        if not active_datasets_to_run:
            logger.error("[bold red]No valid datasets. Aborting.[/bold red]")
            return

        total_tasks = len(active_datasets_to_run) * len(optimizers) * len(MODELS_TO_RUN) # Added MODELS_TO_RUN
        console.print(Rule("Phase 2: Running Optimizations", style="dim blue"))
        console.print(f"Preparing to run [bold cyan]{total_tasks}[/bold cyan] optimization tasks...")
        
        successful_tasks = 0
        failed_tasks = 0

        # If resuming, pre-populate successful_tasks and failed_tasks from checkpoint for accurate live summary
        if self.resuming_run_active:
            for res_summary in self.results: # self.results is loaded from checkpoint
                if res_summary.get("status") == "success":
                    # successful_tasks += 1 # This was causing double counting if resume + retry logic both hit
                    pass # Counted by completed_task_configs_for_resume logic later for progress bar
                elif res_summary.get("status", "").startswith("failure"):
                    # failed_tasks +=1 # Also potentially double counted
                    pass
            # logger.info(f"[green]Resuming: Initialized task counts from checkpoint - Success: {successful_tasks}, Failed: {failed_tasks}[/green]")
            # The actual successful_tasks and failed_tasks for *this run* start at 0.
            # The progress bar and skipping logic will handle counts from previous runs.
            pass # Initializing successful_tasks and failed_tasks to 0 is correct for a new run/retry session.

        completed_results_display = []
        active_tasks_status = {} # {future: {"desc": str, "optimizer": str, "model": str}}

        # Setup Live display components
        progress = Progress(*PROGRESS_COLUMNS, console=console, transient=False, expand=True)
        
        # Determine actual number of tasks that will be submitted for progress bar total
        # This logic needs to be before overall_progress_task is added
        tasks_to_plan_for_progress = []
        for ds_key_prog in active_datasets_to_run:
            for opt_key_prog in optimizers:
                for model_name_prog in MODELS_TO_RUN: # Added model loop
                    # opt_config_prog = OPTIMIZER_CONFIGS[opt_key_prog] # Not needed here, only model name for key
                    # mdl_name_prog = opt_config_prog.get("params", {}).get("model", "N/A")
                    tasks_to_plan_for_progress.append((ds_key_prog, opt_key_prog, model_name_prog))
        
        num_tasks_for_progress_bar = 0
        if self.retry_failed_run_id and self.resuming_run_active and self.results:
            # Count only those in retry_set that are not already completed
            # tasks_to_explicitly_retry_keys was built above, let's re-use a similar build here for clarity before loop
            retry_candidates = set()
            completed_keys_in_retry_run = set()
            for res_summary_prog in self.results:
                if res_summary_prog.get("run_id") == self.retry_failed_run_id: # Must be from the target run
                    task_key_prog_sum = (res_summary_prog["dataset"], res_summary_prog["optimizer"], res_summary_prog["model_name_used"])
                    if res_summary_prog.get("status") != "success":
                        retry_candidates.add(task_key_prog_sum)
                    else:
                        completed_keys_in_retry_run.add(task_key_prog_sum)
            num_tasks_for_progress_bar = len(retry_candidates - completed_keys_in_retry_run)
            logger.info(f"Retry mode: Progress bar total set to {num_tasks_for_progress_bar} (tasks from run '{self.retry_failed_run_id}' to retry).")
        elif self.resuming_run_active and self.results:
            completed_keys_for_resume_prog = set()
            for res_summary_prog in self.results:
                if res_summary_prog.get("status") == "success":
                    completed_keys_for_resume_prog.add((res_summary_prog["dataset"], res_summary_prog["optimizer"], res_summary_prog["model_name_used"]))
            num_tasks_for_progress_bar = len(tasks_to_plan_for_progress) - len(completed_keys_for_resume_prog)
            logger.info(f"Resume mode: Progress bar total set to {num_tasks_for_progress_bar} (total tasks - previously successful).")
        else: # Fresh run
            num_tasks_for_progress_bar = len(tasks_to_plan_for_progress)
            logger.info(f"Fresh run: Progress bar total set to {num_tasks_for_progress_bar}.")

        overall_progress_task = progress.add_task("[bold blue]Overall Progress[/bold blue]", total=max(1, num_tasks_for_progress_bar)) # Ensure total is at least 1

        # Create a unique run_id for this benchmark execution
        self.current_run_id = f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{os.urandom(4).hex()}"
        self.task_results_dir = self.output_dir / "task_results" / self.current_run_id
        self.task_results_dir.mkdir(parents=True, exist_ok=True)
        logger.info(f"Benchmark Run ID: [bold yellow]{self.current_run_id}[/bold yellow]")
        logger.info(f"Task results will be saved in: [blue]{self.task_results_dir}[/blue]")

        summary_line = Text(f"Run: {self.current_run_id} | Tasks: 0/{total_tasks} | Success: 0 | Failed: 0 | Active: 0", style="dim")
        active_tasks_content = Group(Text("No tasks running yet..."))
        active_tasks_panel = Panel(active_tasks_content, title="Active Tasks", border_style="blue", padding=(0,1))
        
        # Define function to generate the renderable for Live display
        def generate_live_display() -> Group:
            active_list = []
            for status_info in active_tasks_status.values():
                desc = status_info.get("desc", "Unknown Task") 
                opt = status_info.get("optimizer", "?") 
                # Use the original model name stored in status_info["model"]
                model_original = status_info.get("model", "?") 
                # Extract dataset from desc (first part before '/')
                try:
                    dataset_part = desc.split('/')[0].replace("Running: ", "").strip()
                    display_text = f" • {dataset_part}/{model_original}" 
                except Exception:
                    display_text = f" • {desc}" # Fallback to full desc
                opt_short = opt.replace("Optimizer", "") # Less aggressive shortening
                active_list.append(
                     Text.assemble((display_text, "yellow"), (f" [{opt_short}]", "dim"))
                 )
                
            if not active_list:
                 active_tasks_content = Group(Text("Waiting for tasks...", style="dim"))
            else:
                 active_tasks_content = Group(*active_list)
                 
            updated_active_panel = Panel(active_tasks_content, title="Active Tasks", border_style="blue", padding=(0,1))
            return Group(progress, Padding(summary_line, (0, 0, 1, 0)), updated_active_panel)

        with Live(console=console, refresh_per_second=4, vertical_overflow="visible") as live:
            live.update(generate_live_display())
            future_to_meta = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                # --- Submission Loop --- 
                tasks_to_plan = []
                for ds_key in active_datasets_to_run:
                    for opt_key in optimizers:
                        for model_to_run in MODELS_TO_RUN: # Added model loop
                            base_opt_config = OPTIMIZER_CONFIGS[opt_key]
                            # Create a copy of the params to avoid modifying the global config
                            current_opt_params = base_opt_config.get("params", {}).copy()
                            current_opt_params["model"] = model_to_run # Set the current model
                            
                            # Create a temporary, model-specific optimizer config for this task
                            current_optimizer_config_for_task = {
                                "class": base_opt_config["class"],
                                "params": current_opt_params
                            }
                            # Sanitize model name for use in IDs/paths
                            sanitized_model_name = model_to_run.replace("/", "-")
                            tasks_to_plan.append((ds_key, opt_key, model_to_run, sanitized_model_name, current_optimizer_config_for_task))

                # Prepare a set of successfully completed task configurations for quick lookup if resuming
                completed_task_keys_from_checkpoint = set()
                # Also, if retrying a specific failed run, identify tasks that actually failed in that run
                tasks_to_explicitly_retry_keys = set() 

                if self.resuming_run_active and self.results: # self.results loaded from a checkpoint
                    for res_summary in self.results:
                        if res_summary.get("dataset") and res_summary.get("optimizer") and res_summary.get("model_name_used"):
                            task_key_from_sum = (
                                res_summary["dataset"],
                                res_summary["optimizer"],
                                res_summary["model_name_used"]
                            )
                            if res_summary.get("status") == "success":
                                completed_task_keys_from_checkpoint.add(task_key_from_sum)

                for dataset_key, optimizer_key, model_name, sanitized_model_name_for_ids, optimizer_config_for_current_task in tasks_to_plan:
                    task_config_tuple_for_check = (dataset_key, optimizer_key, model_name)

                    if self.resuming_run_active and task_config_tuple_for_check in completed_task_keys_from_checkpoint:
                        logger.info(f"Skipping already successfully completed task: {task_config_tuple_for_check}")
                        progress.update(overall_progress_task, advance=1)
                        # Need to count this as a "successful" task for the summary line if it was previously successful
                        # The 'successful_tasks' counter is for tasks completed *in this current execution*.
                        # The summary line might need to reflect tasks from checkpoint vs new tasks.
                        # For now, just advancing overall progress.
                        # To make the live summary accurate, we might need to initialize successful_tasks from checkpoint.
                        # Let's adjust successful_tasks based on checkpoint if resuming.
                        # This will be handled when initializing successful_tasks/failed_tasks before the loop.
                        continue # Skip to the next task configuration

                    task_desc_short = f"{dataset_key}/{optimizer_key}/{sanitized_model_name_for_ids}"
                    dataset_obj = self.dataset_cache[f"{dataset_key}_{self.test_mode}"]
                    project_name_opik = f"benchmark-{self.current_run_id}-{dataset_key}-{optimizer_key}-{sanitized_model_name_for_ids}-{datetime.now().strftime('%Y%m%d%H%M%S')}"
                    optimizer_instance = self.create_optimizer(optimizer_config_for_current_task, project_name_opik)
                    if optimizer_instance is None:
                        logger.error(f"[red]✗ Failed create optimizer {optimizer_key}/{dataset_key} for model {model_name}. Skip.[/red]")
                        failed_tasks += 1
                        progress.update(overall_progress_task, advance=1)
                        summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{total_tasks} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                        live.update(generate_live_display()) # Update display after failure
                        continue # <<< Indent this to be PART of the if block
                        
                    exp_config = get_experiment_config(dataset_key, optimizer_key, model_name, test_mode=self.test_mode)
                    current_future = executor.submit( 
                        self.run_optimization, 
                        dataset=dataset_obj, 
                        optimizer=optimizer_instance, 
                        metrics=DATASET_CONFIGS[dataset_key]["metrics"], 
                        initial_prompt=INITIAL_PROMPTS[dataset_key], 
                        input_key=DATASET_CONFIGS[dataset_key]["input_key"], 
                        output_key=DATASET_CONFIGS[dataset_key]["output_key"], 
                        experiment_config=exp_config
                    )
                    
                    # Store more metadata for the live display
                    task_desc_short = f"{dataset_key}/{optimizer_key}/{sanitized_model_name_for_ids}" # Use sanitized name here too
                    future_to_meta[current_future] = {
                        "dataset_key": dataset_key, 
                        "optimizer_key": optimizer_key, 
                        "desc": task_desc_short, # task_desc_short now includes model_name
                        "optimizer_name": type(optimizer_instance).__name__,
                        "model_name": model_name, # model_name is from the loop
                        "sanitized_model_name": sanitized_model_name_for_ids # Sanitized name
                    }
                    active_tasks_status[current_future] = {
                        "desc": f"Running: {task_desc_short}",
                        "optimizer": type(optimizer_instance).__name__,
                        "model": model_name # Store ORIGINAL model name here
                        # Or maybe model_name? Let's use sanitized for consistency with IDs.
                    }
                    summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{num_tasks_for_progress_bar} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                    live.update(generate_live_display())

                # --- Processing Loop --- 
                for future_item in as_completed(future_to_meta.keys()):
                    meta = future_to_meta[future_item]
                    d_key, o_key = meta["dataset_key"], meta["optimizer_key"]
                    # Use sanitized model name for constructing IDs/filenames
                    sanitized_model_name_for_ids = meta["sanitized_model_name"]
                    model_name_original = meta["model_name"] # Original for logging/display if needed
                    task_desc_short = meta["desc"]
                    run_status_flag = "failure" # Default to failure
                    result_data = None
                    task_id_for_log = "unknown_task"

                    active_tasks_status.pop(future_item, None)
                    
                    try:
                        result_data = future_item.result() # This is the detailed dict from run_optimization
                        if result_data and isinstance(result_data, dict):
                            # Construct task ID and filename using the SANITIZED model name
                            # Ensure sanitized name is consistently used for ID generation
                            timestamp_str = datetime.now().strftime('%Y%m%d%H%M%S%f') 
                            # Use the sanitized name passed via meta dictionary
                            task_id_for_log = f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-{timestamp_str}" 
                            result_data["task_id"] = task_id_for_log # Update result data with the final ID
                            task_json_filename = f"{task_id_for_log}.json" # Use the consistent ID for filename
                            task_json_path = self.task_results_dir / task_json_filename
                            logger.debug(f"Constructed task JSON path: {task_json_path}") # Log the path
                            try:
                                # Ensure directory exists 
                                self.task_results_dir.mkdir(parents=True, exist_ok=True)
                                with open(task_json_path, "w") as f_task_json:
                                    json.dump(result_data, f_task_json, indent=2, default=custom_json_serializer)
                                logger.info(f"Saved task result: [blue]{task_json_path}[/blue]")
                            except Exception as e_json_save:
                                logger.error(f"[red]Failed to save task JSON for {task_id_for_log}: {e_json_save}[/red]")

                            # Update self.results with metadata, not the full data
                            task_summary_for_results_list = {
                                "task_id": task_id_for_log,
                                "run_id": self.current_run_id,
                                "dataset": result_data.get("config", {}).get("dataset_config_name"),
                                "optimizer": result_data.get("config", {}).get("optimizer_config_name"),
                                "status": result_data.get("status", "failure"),
                                "timestamp_end_task": result_data.get("timestamp_end_task"),
                                "duration_seconds_task": result_data.get("duration_seconds_task"),
                                "json_file_path": str(task_json_path.resolve()),
                                "model_name_used": model_name_original, # Store original model name here
                                # Store only key final metrics for quick summary, actuals are in JSON
                                "final_primary_score": None, # Placeholder
                            }
                            
                            if result_data.get("status") == "success":
                                successful_tasks += 1
                                run_status_flag = "success"
                                status_message = f"[green]✓ Completed: {task_id_for_log}[/green]"                                
                                final_eval_data = result_data.get("final_evaluation")
                                
                                if final_eval_data is not None:
                                    final_eval_metrics = final_eval_data.get("metrics", [])
                                    if final_eval_metrics and isinstance(final_eval_metrics, list) and len(final_eval_metrics) > 0:
                                        task_summary_for_results_list["final_primary_score"] = final_eval_metrics[0].get("score")
                            else:
                                logger.warning(f"[yellow]Task {task_id_for_log} reported status: {result_data.get('status')}. Error: {result_data.get('error_message')}. Marked failed.[/yellow]")
                                failed_tasks += 1
                                status_message = f"[red]✗ Failed ({result_data.get('status')}): {task_id_for_log}[/red]"
                            
                            self.results.append(task_summary_for_results_list)

                        else: # result_data is None or not a dict (should not happen with new run_optimization)
                            logger.warning(f"[yellow]Task {d_key}/{o_key} (meta desc: {task_desc_short}) returned invalid data (None or not dict). Marked failed.[/yellow]")
                            failed_tasks += 1
                            status_message = f"[red]✗ Failed (Invalid Data): {task_desc_short}[/red]"
                            # Append a basic failure entry to self.results if result_data is bad
                            self.results.append({
                                "task_id": f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-invalid_data", # Use sanitized name
                                "run_id": self.current_run_id,
                                "dataset": d_key, # from meta
                                "optimizer": o_key, # from meta
                                "status": "failure_system_error",
                                "error_message": "Optimizer task returned None or invalid data structure.",
                                "json_file_path": None,
                            })

                    except Exception as e_future:
                        task_id_for_log = f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-future_exception"
                        logger.error(f"[red]Exception processing future for {d_key}/{o_key} (Model: {model_name_original}): {e_future}[/red]")
                        logger.exception("Traceback for future processing error:")
                        failed_tasks += 1
                        status_message = f"[red]✗ Failed (Exc Future): {task_desc_short}[/red]"
                        # Append a basic failure entry to self.results for future exceptions
                        self.results.append({
                            "task_id": f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-future_exception", # Use sanitized name
                            "run_id": self.current_run_id,
                            "dataset": d_key,
                            "optimizer": o_key,
                            "status": "failure_exception_processing_future",
                            "error_message": str(e_future),
                            "json_file_path": None,
                        })
                    finally:
                        # Create panel for final display using info from result_data if available, or meta if not
                        display_dataset_name = DATASET_CONFIGS[d_key]["name"]
                        display_optimizer_name = o_key
                        metrics_to_display = {}
                        time_taken_display = 0
                        opt_details_summary = {}

                        # Check if result_data exists before accessing it
                        if result_data: # Check if the future completed and returned data
                            # print("\n=== DEBUG: Result Panel Creation ===")
                            # print(f"Result Data Keys: {list(result_data.keys())}")
                            # Safely get status, default to failure if key missing
                            task_status = result_data.get("status", "failure") 
                            # print(f"Task Status: {task_status}")
                            
                            if task_status == "success":
                                # Populate for success case (safe access needed here too)
                                initial_eval_data = result_data.get("initial_evaluation") 
                                initial_metrics = initial_eval_data.get("metrics", []) if initial_eval_data is not None else []
                                final_eval_data = result_data.get("final_evaluation")
                                final_eval_metrics = final_eval_data.get("metrics", []) if final_eval_data is not None else []
                                
                                # Get raw optimizer result data
                                raw_result = result_data.get("raw_optimizer_result", {})
                                initial_scores_panel = {m.get("metric_name", "unk"): m.get("score") for m in initial_metrics if isinstance(m, dict)} 
                                final_scores_panel = {f"Final {m.get('metric_name', 'unk')}": m.get("score") for m in final_eval_metrics if isinstance(m, dict)}
                                metrics_to_display = {**initial_scores_panel, **final_scores_panel}
                                # Set time_taken_display for success case
                                time_taken_display = result_data.get("duration_seconds_task", 0)
                                opt_proc = result_data.get("optimization_process", {})
                                opt_details_summary = {
                                    "num_iterations": len(opt_proc.get("history", [])),
                                    "best_score": opt_proc.get("best_score_achieved"),
                                    "optimization_history": opt_proc.get("history", [])
                                }
                        
                        else: # result_data is None (exception occurred during future processing)
                             opt_details_summary = {"error": f"Task failed during execution (check logs for {d_key}/{o_key})"}
                             run_status_flag = "failure" # Ensure panel shows failure

                        # Call create_result_panel with potentially empty/error data
                        result_panel = create_result_panel(
                            display_dataset_name,
                            display_optimizer_name,
                            metrics_to_display, # Will be {} if failed or no data
                            time_taken_display, # Will be 0 if no data
                            opt_details_summary, # Will contain error if failed or no data
                            run_status_flag # Should correctly be failure if needed
                        )
                        # Append panel, handle potential None result_data if needed later (e.g. for final prompt)
                        completed_results_display.append((result_panel, result_data)) 
                        
                        # Update progress etc.
                        progress.update(overall_progress_task, advance=1)
                        # Use num_tasks_for_progress_bar for the total in the summary line
                        summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{num_tasks_for_progress_bar} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                        live.update(generate_live_display())
                        
                        self.save_checkpoint() # Save checkpoint after each task (implicitly saves self.results)
                        self.save_results() # Save CSV summary incrementally

        # --- End of Live Block --- 
        overall_duration = time.time() - overall_start_time
        print_benchmark_footer(self.results, successful_tasks, failed_tasks, overall_duration, completed_results_display)

    def save_results(self):
        """Save summary results to CSV. Detailed results are already saved as individual task JSONs."""
        if not self.results: # self.results now contains task metadata summaries
            logger.info("[yellow]No task metadata in self.results to generate CSV summary.[/yellow]")
            return

        # Ensure current_run_id and task_results_dir are available for naming/pathing the summary CSV
        if not hasattr(self, 'current_run_id') or not self.current_run_id or not hasattr(self, 'task_results_dir') or not self.task_results_dir:
             logger.warning("[yellow]Cannot save summary CSV: run_id or task_results_dir not set.[/yellow]")
             return
             
        run_id_for_filename = self.current_run_id 
        csv_filename = f"run_summary_{run_id_for_filename}.csv"
        # Save the CSV inside the run-specific task results directory
        csv_path_abs = (self.task_results_dir / csv_filename).resolve()

        logger.info(f"Generating/Updating summary CSV: [blue]{csv_path_abs}[/blue]")

        flat_data_for_csv = []
        for task_summary in self.results:
            # Default item data for CSV, including failures/skips
            item_data = {
                "run_id": task_summary.get("run_id"),
                "task_id": task_summary.get("task_id"),
                "dataset": task_summary.get("dataset"),
                "optimizer": task_summary.get("optimizer"),
                "model_name": task_summary.get("model_name_used"),
                "status": task_summary.get("status", "unknown"),
                "duration_seconds_task": task_summary.get("duration_seconds_task"),
                "error_message": None, 
                "json_file_path": task_summary.get("json_file_path")
            }

            json_path = task_summary.get("json_file_path")
            if task_summary.get("status") != "success" or not json_path:
                # If failed/skipped and we have a JSON path, try to get error message
                if json_path:
                    try:
                        with open(json_path, "r") as f_detail:
                            detail_data = json.load(f_detail)
                        item_data["error_message"] = detail_data.get("error_message")
                    except Exception: 
                        item_data["error_message"] = "(Failed to read details from JSON)"
                else:
                     item_data["error_message"] = task_summary.get("error_message", "(No JSON path found)") # Error might be in summary
                
                flat_data_for_csv.append(item_data)
                continue # Skip detailed score processing for non-success

            try:
                with open(json_path, "r") as f_detail:
                    detail_data = json.load(f_detail)
                
                # Update basic info just in case summary was incomplete
                config_data = detail_data.get("config", {})
                optimizer_params = config_data.get("optimizer_params", {})
                item_data["run_id"] = detail_data.get("run_id", item_data["run_id"])
                item_data["task_id"] = detail_data.get("task_id", item_data["task_id"])
                item_data["dataset"] = config_data.get("dataset_config_name", item_data["dataset"])
                item_data["optimizer"] = config_data.get("optimizer_config_name", item_data["optimizer"])
                # Prioritize model_name from the new config field
                item_data["model_name"] = config_data.get("model_name", item_data["model_name"])
                if not item_data["model_name"] or item_data["model_name"] == "N/A": # Fallback
                    item_data["model_name"] = optimizer_params.get("model", "N/A")
                item_data["status"] = detail_data.get("status", item_data["status"])
                item_data["duration_seconds_task"] = detail_data.get("duration_seconds_task", item_data["duration_seconds_task"])
                item_data["initial_prompt_template"] = config_data.get("initial_prompt")
                item_data["dataset_size"] = None # Placeholder - needs better source

                # Flatten initial scores
                initial_eval = detail_data.get("initial_evaluation", {})
                if initial_eval and isinstance(initial_eval.get("metrics"), list):
                    for metric_res in initial_eval["metrics"]:
                        # Use cleaned name for CSV header key
                        metric_name_cleaned = clean_metric_name(str(metric_res.get("metric_name", "unknown_metric")))
                        metric_name_for_header = metric_name_cleaned.replace(" ", "_").replace(".", "_") 
                        item_data[f"initial_{metric_name_for_header}_score"] = metric_res.get("score")

                # Flatten final scores
                final_eval = detail_data.get("final_evaluation", {})
                if final_eval and isinstance(final_eval.get("metrics"), list):
                    for metric_res in final_eval["metrics"]:
                         # Use cleaned name for CSV header key
                        metric_name_cleaned = clean_metric_name(str(metric_res.get("metric_name", "unknown_metric")))
                        metric_name_for_header = metric_name_cleaned.replace(" ", "_").replace(".", "_") 
                        item_data[f"final_{metric_name_for_header}_score"] = metric_res.get("score")
                
                # Optimization process details
                opt_process = detail_data.get("optimization_process", {})
                # Correctly source num_iterations for FewShotBayesianOptimizer
                if config_data.get("optimizer_class") == "FewShotBayesianOptimizer":
                    item_data["opt_num_iterations"] = opt_process.get("num_trials_configured")
                else:
                    item_data["opt_num_iterations"] = len(opt_process.get("history", [])) # Default for round-based
                item_data["opt_best_score_achieved"] = opt_process.get("best_score_achieved")
                item_data["opt_duration_seconds"] = opt_process.get("duration_seconds")

                flat_data_for_csv.append(item_data)

            except FileNotFoundError:
                logger.error(f"[red]JSON file not found for task {task_summary.get('task_id')}: {task_summary.get('json_file_path')}[/red]")
            except json.JSONDecodeError:
                logger.error(f"[red]Error decoding JSON for task {task_summary.get('task_id')}: {task_summary.get('json_file_path')}[/red]")
            except Exception as e:
                logger.error(f"[red]Error processing task JSON {task_summary.get('json_file_path')} for CSV: {e}[/red]")

        if flat_data_for_csv:
            try:
                df = pd.DataFrame(flat_data_for_csv)
                df.to_csv(csv_path_abs, index=False)
                csv_path_abs_str = str(csv_path_abs)
                console.print(
                    Text.assemble(
                        ("✓ Summary CSV saved to: ", "bold green"),
                        (csv_path_abs_str, f"link file://{csv_path_abs_str} cyan")
                    )
                )
            except Exception as e_csv_save:
                logger.error(f"[red]Failed to save summary CSV to {csv_path_abs}: {e_csv_save}[/red]")
                logger.exception("Traceback for CSV saving:")
        else:
            logger.info("[yellow]No suitable data processed to create CSV summary.[/yellow]")
        
        # self.save_checkpoint() # Checkpoint is now saved after each task, not here.
        # The detailed JSON results (per task) are also saved progressively.
        
    def print_summary(self):
        """Print summary of results using Rich table (this is called by print_benchmark_footer)."""
        if not self.results:
            console.print("[yellow]No results to summarize (self.results is empty).[/yellow]")
            return
            
        # This method is effectively replaced by print_benchmark_footer's logic,
        # but if called directly, it should behave like print_benchmark_footer.
        print_benchmark_footer(self.results)

def create_result_panel(dataset_name: str, optimizer_name: str, metrics: dict, time_taken: float, optimization_details: dict, run_status: str) -> Panel:
    """Create a consistent panel for displaying optimization results."""
    table = Table.grid(padding=(0, 2), expand=True)
    table.add_column(style="dim", width=18)
    table.add_column()

    table.add_row("Dataset:", f"[bold]{dataset_name}[/bold]")
    table.add_row("Optimizer:", f"[bold]{optimizer_name}[/bold]")
    # Ensure time_taken is displayed correctly
    table.add_row("Time Taken:", f"{time_taken:.2f}s" if isinstance(time_taken, (int, float)) else "[dim]N/A[/dim]")

    # --- Scores --- 
    score_rows = []
    initial_scores_grp = []
    final_scores_grp = []
    percent_changes_grp = []

    initial_score_values = {}
    final_score_values = {}
    metric_names_ordered = []

    # Function to clean metric name representation
    def clean_metric_name(metric_key_str: str) -> str:
        # Extracts 'LevenshteinRatio' from '<...LevenshteinRatio object at ...>'
        # Or handles already clean names
        if '<' in metric_key_str and 'object at' in metric_key_str:
             # Extract the class name part more robustly
             parts = metric_key_str.split('.')
             if len(parts) > 1:
                 name = parts[-1]
                 if ' object at' in name:
                     name = name.split(' object at')[0]
                 return name
             else: # Fallback if format is unexpected
                 return metric_key_str.strip('<> ') 
        return metric_key_str # Return as is if not matching the object format

    # Process initial first to establish order and values
    for metric_key, value in metrics.items():
        if not str(metric_key).startswith("Final "):
            metric_name_str = clean_metric_name(str(metric_key)) # Use cleaner function
            if metric_name_str not in initial_score_values:
                metric_names_ordered.append(metric_name_str)
            initial_score_values[metric_name_str] = value
            style = STYLES["success"] if isinstance(value, (int, float)) else STYLES["warning"]
            value_str = f"{value:.4f}" if isinstance(value, (int,float)) else ("[dim]N/A[/dim]" if value is None else str(value))
            initial_scores_grp.append(Text.assemble(f" • {metric_name_str}: ", (value_str, style)))

    # Process final, calculating percentage change if possible
    for metric_key, value in metrics.items():
        if str(metric_key).startswith("Final "):
            base_metric_name = clean_metric_name(str(metric_key).replace("Final ", "", 1)) # Use cleaner function
            if base_metric_name not in metric_names_ordered: metric_names_ordered.append(base_metric_name) # Add if only final exists
            final_score_values[base_metric_name] = value
            style = STYLES["success"] if isinstance(value, (int, float)) else STYLES["warning"]
            value_str = f"{value:.4f}" if isinstance(value, (int,float)) else ("[dim]N/A[/dim]" if value is None else str(value))
            final_scores_grp.append(Text.assemble(f" • {base_metric_name}: ", (value_str, style)))
            # Calculate percentage change
            initial_val = initial_score_values.get(base_metric_name)
            percent_change_text = calculate_percentage_change(initial_val, value, base_metric_name)
            percent_changes_grp.append(Text.assemble(f" • {base_metric_name}: ", percent_change_text))

    if initial_scores_grp:
        score_rows.append(Text("Initial Scores:", style="underline"))
        score_rows.append(Group(*initial_scores_grp))
    if final_scores_grp:
        score_rows.append(Text("Final Scores:", style="underline"))
        score_rows.append(Group(*final_scores_grp))
    if percent_changes_grp:
        score_rows.append(Text("% Change:", style="underline"))
        score_rows.append(Group(*percent_changes_grp))
        
    if score_rows:
        table.add_row("Scores:", Group(*score_rows))
    else:
        table.add_row("Scores:", Text("[dim]N/A[/dim]"))
        
    # --- Optimization Details --- 
    # Handle potentially missing or non-numeric iterations
    iter_val = optimization_details.get("num_iterations") # Could be None, N/A, or number
    iter_str = "[dim]N/A[/dim]"
    if isinstance(iter_val, int):
        iter_str = str(iter_val)
    elif iter_val is not None: # Handle "N/A" string or other non-numeric values
        iter_str = str(iter_val) 

    best_score_val = optimization_details.get("best_score") # Could be None
    best_score_str = f"{best_score_val:.4f}" if isinstance(best_score_val, (int, float)) else "[dim]N/A[/dim]"

    table.add_row("Iterations:", iter_str)
    table.add_row("Best Score (Opt):", best_score_str)

    # --- History Summary --- 
    # Check if optimization_details contains an error message first
    if "error" in optimization_details:
        table.add_row("Opt. Details:", Text(f"[red]Error: {optimization_details['error'][:100]}...[/red]", overflow="ellipsis"))
    else:
        history = optimization_details.get("optimization_history", [])
        if history and isinstance(history, list):
            history_summary_parts = []
            limit = 4 # Show first 2 and last 2 if > limit
            if len(history) > limit:
                for i, round_data in enumerate(history[:limit//2]):
                    if isinstance(round_data, dict):
                        score_hist = round_data.get('score', round_data.get('current_score', 'N/A'))
                        score_hist_str = f"{score_hist:.4f}" if isinstance(score_hist, (int, float)) else str(score_hist)
                        history_summary_parts.append(f"  R{round_data.get('round_number', i)}: {score_hist_str}")
                history_summary_parts.append("  ...")
                for i, round_data in enumerate(history[-limit//2:], start=len(history)-limit//2):
                    if isinstance(round_data, dict):
                        score_hist = round_data.get('score', round_data.get('current_score', 'N/A'))
                        score_hist_str = f"{score_hist:.4f}" if isinstance(score_hist, (int, float)) else str(score_hist)
                        history_summary_parts.append(f"  R{round_data.get('round_number', i)}: {score_hist_str}")
            else:
                for i, round_data in enumerate(history):
                    if isinstance(round_data, dict):
                        score_hist = round_data.get('score', round_data.get('current_score', 'N/A'))
                        score_hist_str = f"{score_hist:.4f}" if isinstance(score_hist, (int, float)) else str(score_hist)
                        history_summary_parts.append(f"  R{round_data.get('round_number', i)}: {score_hist_str}")
            if history_summary_parts:
                table.add_row("Score History:", Group(*history_summary_parts))

    border_color = "green" if run_status == "success" else ("red" if run_status == "failure" else "yellow")
    title_status = f"[{border_color.upper()}]"
    panel_title = Text.assemble(title_status, f" {optimizer_name} on {dataset_name}")
    
    return Panel( table, title=panel_title, border_style=border_color, padding=(1, 2), expand=False)

def print_benchmark_header(datasets: List[str], optimizers: List[str], test_mode: bool):
    """Print a clean header for the benchmark run."""
    console.print(Rule("[bold blue]Benchmark Configuration[/bold blue]", style="blue"))
    
    table = Table(box=box.ROUNDED, show_header=False, padding=(0, 1))
    table.add_row("Datasets", ", ".join(datasets), style=STYLES["header"])
    table.add_row("Optimizers", ", ".join(optimizers), style=STYLES["header"])
    table.add_row("Test Mode", str(test_mode), style=STYLES["info"])
    
    console.print(Panel(table, border_style="blue", padding=(1, 2)))
    console.print()

def print_benchmark_footer(results: List[dict], successful_tasks: int, failed_tasks: int, total_duration: float, completed_display_items: List[Tuple[Panel, Dict]]):
    """Print footer with stats, pivoted results table, and individual panels+prompts."""
    console.print(Rule("[bold blue]Benchmark Run Complete[/bold blue]", style="blue"))
    
    # --- Overall Statistics Panel --- 
    summary_table = Table(box=box.ROUNDED, show_header=False, padding=(0,1), show_edge=False)
    summary_table.add_row("Total Benchmarks Run:", f"[bold cyan]{successful_tasks + failed_tasks}[/bold cyan]")
    summary_table.add_row("Successful Tasks:", f"[bold green]{successful_tasks}[/bold green]")
    summary_table.add_row("Failed Tasks:", f"[bold red]{failed_tasks}[/bold red]")
    summary_table.add_row("Total Duration:", f"[cyan]{total_duration:.2f}s[/cyan]")
    console.print(Panel(summary_table, title="Overall Statistics", border_style="blue", padding=(1,2), expand=False))

    # --- Detailed Pivoted Results Table --- 
    # The `results` argument here is `self.results` from BenchmarkRunner, which is a list of task summary dicts.
    if results: 
        logger.info("Generating detailed pivoted results table for footer...")
        results_table = Table(box=box.SIMPLE_HEAVY, show_header=True, header_style=STYLES["header"], title="Detailed Results Summary", title_style="dim", show_lines=True, padding=(0,1,0,1))
        results_table.add_column("Dataset", style=STYLES["dim"], max_width=25, overflow="ellipsis", no_wrap=True)
        results_table.add_column("Optimizer", no_wrap=True)
        results_table.add_column("Model", no_wrap=True, max_width=20, overflow="ellipsis") # Added Model column
        results_table.add_column("Metric", no_wrap=True)
        results_table.add_column("Time (s)", justify="right", no_wrap=True)
        results_table.add_column("Initial", justify="right", no_wrap=True)
        results_table.add_column("Final", justify="right", no_wrap=True)
        results_table.add_column("% Change", justify="right", no_wrap=True)
        
        all_metrics_names = set()
        # processed_rows will store data indexed by (dataset_name, optimizer_name, model_name)
        # Each value will be like: {"initial": {metric_name: score}, "final": {metric_name: score}, "time": time_taken, "task_id": ...}
        processed_data_for_table = {}

        for task_summary in results: # task_summary is an item from self.results
            if task_summary.get("status") != "success" or not task_summary.get("json_file_path"):
                continue # Skip non-successful or tasks without detailed JSON for this table

            try:
                with open(task_summary["json_file_path"], "r") as f_detail:
                    detail_data = json.load(f_detail)
                
                config = detail_data.get("config", {})
                dataset_name = config.get("dataset_config_name", "N/A")
                optimizer_name = config.get("optimizer_config_name", "N/A")
                model_name = config.get("model_name", "N/A") 
                time_taken = detail_data.get("duration_seconds_task", 0)
                task_id = detail_data.get("task_id", "N/A")

                table_key = (dataset_name, optimizer_name, model_name)
                if table_key not in processed_data_for_table:
                    processed_data_for_table[table_key] = {
                        "initial": {},
                        "final": {},
                        "time": time_taken,
                        "task_id": task_id # Store task_id for reference if needed
                    }
                else: # Should not happen if task_ids are unique per config, but handle defensively
                    processed_data_for_table[table_key]["time"] = time_taken 
                
                initial_eval_metrics = detail_data.get("initial_evaluation", {}).get("metrics", [])
                for metric_entry in initial_eval_metrics:
                    metric_display_name = str(metric_entry.get("metric_name", "Unknown")).split(" object at")[0].split('.')[-1]
                    all_metrics_names.add(metric_display_name)
                    processed_data_for_table[table_key]["initial"][metric_display_name] = metric_entry.get("score")

                final_eval_metrics = detail_data.get("final_evaluation", {}).get("metrics", [])
                for metric_entry in final_eval_metrics:
                    metric_display_name = str(metric_entry.get("metric_name", "Unknown")).split(" object at")[0].split('.')[-1]
                    all_metrics_names.add(metric_display_name)
                    processed_data_for_table[table_key]["final"][metric_display_name] = metric_entry.get("score")

            except FileNotFoundError:
                logger.warning(f"[yellow]Footer Table: JSON file not found for task {task_summary.get('task_id')}: {task_summary.get('json_file_path')}[/yellow]")
            except json.JSONDecodeError:
                logger.warning(f"[yellow]Footer Table: Error decoding JSON for task {task_summary.get('task_id')}: {task_summary.get('json_file_path')}[/yellow]")
            except Exception as e:
                logger.warning(f"[yellow]Footer Table: Error processing task JSON {task_summary.get('json_file_path')} for table: {e}[/yellow]")

        # Sort by dataset, then optimizer, then model for consistent table output
        sorted_table_keys = sorted(processed_data_for_table.keys())
        
        last_dataset_optimizer_model = None # Used for section breaks
        for i, key_tuple in enumerate(sorted_table_keys):
            dataset, optimizer, model = key_tuple
            data_for_run_key = processed_data_for_table[key_tuple]
            time_taken_for_run = data_for_run_key.get("time", 0)
            scores_by_metric = {"initial": data_for_run_key["initial"], "final": data_for_run_key["final"]}
            
            is_new_block = (last_dataset_optimizer_model != key_tuple)
            
            for metric_i, metric_name_to_display in enumerate(sorted(list(all_metrics_names))):
                initial_val = scores_by_metric["initial"].get(metric_name_to_display)
                final_val = scores_by_metric["final"].get(metric_name_to_display)
                
                if initial_val is not None or final_val is not None: # Only show rows if at least one score exists
                    initial_str = f"{initial_val:.4f}" if isinstance(initial_val, (int, float)) else "[dim]-[/dim]"
                    final_str = f"{final_val:.4f}" if isinstance(final_val, (int, float)) else "[dim]-[/dim]"
                    percent_change_text = calculate_percentage_change(initial_val, final_val, metric_name_to_display)
                    
                    dataset_text = Text(dataset, overflow="ellipsis")
                    dataset_text.truncate(25)
                    # Split model name by '/' and join with newline
                    model_parts = model.split('/')
                    model_text = Text('\n'.join(model_parts), overflow="ellipsis")
                    model_text.truncate(20)
                    
                    # Display dataset, optimizer, model, and time only for the first metric of a block
                    display_dataset = dataset_text if metric_i == 0 else ""
                    display_optimizer = optimizer if metric_i == 0 else ""
                    display_model = model_text if metric_i == 0 else ""
                    display_time = f"{time_taken_for_run:.2f}" if metric_i == 0 else ""
                    
                    # Add a line (end_section) before a new block, but not for the very first block
                    end_section_flag = is_new_block and i > 0 and metric_i == 0

                    results_table.add_row(
                        display_dataset,
                        display_optimizer,
                        display_model, # Added model display
                        metric_name_to_display,
                        display_time,
                        initial_str,
                        final_str,
                        percent_change_text,
                        end_section=end_section_flag
                    )
            last_dataset_optimizer_model = key_tuple
        
        if not processed_data_for_table:
             console.print("[yellow]No successful task data to display in pivoted results table.[/yellow]")
        else:
            console.print(Panel(results_table, border_style="blue", padding=(1, 2), title_align="left"))
    else:
        console.print("[yellow]No results (task summaries) available to generate detailed summary table.[/yellow]")

    # --- Individual Task Panels + Final Prompts --- 
    console.print(Rule("Individual Task Results & Final Prompts", style="dim blue"))
    if completed_display_items:
        for panel, result_data in completed_display_items:
            console.print(panel)
            
            if result_data and result_data.get("final_prompt") is not None:
                final_prompt = result_data["final_prompt"]
                prompt_content: Any = "[dim]N/A[/dim]"
                if isinstance(final_prompt, list): # Handle chat format
                    prompt_content = Group(*[
                        Text.assemble(
                            # Select color based on role - Use Style(color=...) for better control
                            (f"{msg.get('role', 'unk').capitalize()}: ", 
                             Style(color='blue' if msg.get('role') == 'system' else ('green' if msg.get('role') == 'user' else 'magenta'), bold=True)), 
                            # Add Text object for content (removed soft_wrap)
                            Text(f"{msg.get('content', '')}") 
                        )
                        for msg in final_prompt if isinstance(msg, dict) # Ensure msg is a dict
                    ])
                elif isinstance(final_prompt, str):
                    # Use Text object for string prompts as well for wrapping (removed soft_wrap)
                    prompt_content = Text(final_prompt)
                
                # Ensure the panel itself doesn't prevent wrapping (default should be ok)
                prompt_panel = Panel(prompt_content, title="Final Prompt", border_style="dim", padding=1)
                console.print(prompt_panel)
            else:
                console.print(Panel("[dim]Final prompt not available for this task.[/dim]", title="Final Prompt", border_style="dim"))
            console.print() # Add space between task outputs
    else:
        console.print("[yellow]No individual task panels were generated.[/yellow]")

def calculate_percentage_change(initial: Optional[float], final: Optional[float], metric_name: str) -> Text:
    # Define metrics where lower is better
    LOWER_IS_BETTER = ["hallucination", "contextprecision"]
    metric_name_lower = metric_name.lower()
    lower_is_better = any(term in metric_name_lower for term in LOWER_IS_BETTER)
    
    if not isinstance(initial, (int, float)) or not isinstance(final, (int, float)):
        return Text("N/A", style=STYLES["dim"])
    if initial == final:
        return Text("No Change", style=STYLES["dim"])
    if initial == 0:
        if final > 0: style = STYLES["error"] if lower_is_better else STYLES["success"]
        elif final < 0: style = STYLES["success"] if lower_is_better else STYLES["error"]
        else: return Text("N/A", style=STYLES["dim"]) # Should not happen if initial==final check passed
        return Text(f"{'+' if final > 0 else ''}Inf%", style=style)
    
    change = (final - initial) / abs(initial)
    if lower_is_better: style = STYLES["success"] if change < 0 else STYLES["error"]
    else: style = STYLES["success"] if change > 0 else STYLES["error"]
        
    return Text(f"{change:+.2%}", style=style)

def main():
    """Main function to run benchmarks with improved output formatting."""
    t_start = time.perf_counter()
    setup_logging(level=logging.INFO, force=True)
    t_log_setup = time.perf_counter()
    logger.info(f"Initial logging setup took {t_log_setup - t_start:.4f}s")
    
    # --- Aggressive Logger Suppression --- 
    # Silence opik core directly and forcefully
    opik_logger = logging.getLogger("opik")
    opik_logger.setLevel(logging.CRITICAL)
    opik_logger.propagate = False
    for handler in opik_logger.handlers[:]: # Remove existing handlers
        opik_logger.removeHandler(handler)
    opik_logger.addHandler(logging.NullHandler()) # Add NullHandler to prevent "no handler" warnings
    logger.info("[yellow]Forcefully silenced 'opik' core logger (Level CRITICAL, NullHandler).[/yellow]")
    
    # Silence tqdm via logger
    tqdm_logger = logging.getLogger("tqdm")
    tqdm_logger.setLevel(logging.CRITICAL)
    tqdm_logger.propagate = False
    for handler in tqdm_logger.handlers[:]: tqdm_logger.removeHandler(handler)
    tqdm_logger.addHandler(logging.NullHandler())
    logger.info("[yellow]Forcefully silenced 'tqdm' logger (Level CRITICAL, NullHandler).[/yellow]")
    
    # Silence other common noisy libraries
    noisy_libs = [ "LiteLLM", "urllib3", "requests", "httpx", "dspy", "datasets", "optuna", "filelock" ]
    for lib_name in noisy_libs:
        lib_logger = logging.getLogger(lib_name)
        lib_logger.setLevel(logging.WARNING) # Use WARNING for these, CRITICAL might hide real issues
        # Optionally remove handlers/add NullHandler if WARNING isn't enough
    logger.info(f"[yellow]Set level to WARNING for: {', '.join(noisy_libs)}[/yellow]")
    # ------------------------------------
    t_log_suppress = time.perf_counter()
    logger.info(f"Logger suppression took {t_log_suppress - t_log_setup:.4f}s")

    # Print Header Banner
    console.print(Panel(Align.center("[bold cyan]🚀 Opik Optimizer Benchmark Suite 🚀[/bold cyan]"), border_style="bold blue", padding=1))
    console.print()
    
    # Argument parsing...
    parser = argparse.ArgumentParser(description="Run benchmarks for prompt optimization")
    parser.add_argument("--output-dir", type=str, default="benchmark_results", help="Directory to save benchmark results")
    parser.add_argument("--max-workers", type=int, default=4, help="Maximum number of worker threads")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    parser.add_argument("--test-mode", action="store_true", default=False, help="Run in test mode with 5 examples per dataset")
    parser.add_argument("--resume", action="store_true", default=False, help="Attempt to resume from latest checkpoint, skipping successfully completed tasks")
    # Add arguments for specifying datasets and optimizers
    parser.add_argument("--datasets", type=str, nargs='*', default=None, help=f"Space-separated list of dataset keys to run. Available: {list(DATASET_CONFIGS.keys())}")
    parser.add_argument("--optimizers", type=str, nargs='*', default=None, help=f"Space-separated list of optimizer keys to run. Available: {list(OPTIMIZER_CONFIGS.keys())}")
    parser.add_argument("--retry-failed-run", type=str, default=None, metavar="RUN_ID", help="Specify a previous RUN_ID to retry only its failed tasks. Successful tasks from that run will be skipped.")

    args = parser.parse_args()

    # If no specific operational arguments are given (other than defaults for output_dir, max_workers, seed)
    # and not in test_mode, and not resuming, show help and ask for confirmation.
    operational_args_provided = any([
        args.test_mode,
        args.resume,
        args.datasets is not None,
        args.optimizers is not None,
        # Check if non-default values were provided for these
        args.output_dir != parser.get_default("output_dir"),
        args.max_workers != parser.get_default("max_workers"),
        args.seed != parser.get_default("seed"),
    ])

    if not operational_args_provided and sys.stdin.isatty(): # Only prompt if interactive
        parser.print_help()
        console.print("\n[bold yellow]No specific benchmark parameters or resume flag provided.[/bold yellow]")
        console.print("This will run ALL datasets and ALL optimizers in full mode.")
        try:
            if input("Are you sure you want to continue? (y/N): ").strip().lower() != 'y':
                console.print("Exiting.")
                sys.exit(0)
        except KeyboardInterrupt:
            console.print("\nExiting due to user interruption.")
            sys.exit(0)

    t_args_parsed = time.perf_counter()
    logger.info(f"Argument parsing took {t_args_parsed - t_log_suppress:.4f}s")

    output_path = Path(args.output_dir)
    output_path.mkdir(exist_ok=True)
    
    logger.info("Initializing BenchmarkRunner...")
    t_runner_start = time.perf_counter()
    runner = BenchmarkRunner(
        output_dir=args.output_dir,
        max_workers=args.max_workers,
        seed=args.seed,
        test_mode=args.test_mode,
        resume_enabled=args.resume,
        retry_failed_run_id=args.retry_failed_run
    )
    t_runner_end = time.perf_counter()
    logger.info(f"BenchmarkRunner initialized in {t_runner_end - t_runner_start:.4f}s")
    
    # Run the benchmark 
    logger.info("Starting benchmark run...")
    t_run_start = time.perf_counter()
    runner.run_benchmark(
        datasets=args.datasets if args.datasets else list(DATASET_CONFIGS.keys()),
        optimizers=args.optimizers if args.optimizers else list(OPTIMIZER_CONFIGS.keys())
    )
    t_run_end = time.perf_counter()
    logger.info(f"Benchmark run method finished in {t_run_end - t_run_start:.4f}s")

if __name__ == "__main__":
    main() 