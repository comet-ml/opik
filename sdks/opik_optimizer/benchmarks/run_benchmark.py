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
import io
import os
import dspy
import pandas as pd
import copy

# Rich imports
from rich.progress import Progress, SpinnerColumn, BarColumn, TextColumn, TimeElapsedColumn, TimeRemainingColumn, ProgressColumn, TaskProgressColumn
from rich.panel import Panel
from rich.console import Console, Group
from rich.rule import Rule
from rich import print as rprint
from rich.table import Table
from rich.text import Text
from rich.console import Console
from rich.layout import Layout
from rich.spinner import Spinner
from rich.style import Style
from rich.live import Live
from rich.align import Align
from rich import box
from rich.status import Status
from rich.padding import Padding

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
    EvolutionaryOptimizer,
)
from opik_optimizer.demo import get_or_create_dataset
from opik_optimizer.cache_config import initialize_cache, clear_cache
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision
from opik_optimizer.logging_config import setup_logging
from opik_optimizer import utils as opik_utils
from opik_optimizer import optimization_result as opik_opt_result
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.llm_judges.context_recall.metric import ContextRecall

from benchmark_config import (
    DATASET_CONFIGS, 
    OPTIMIZER_CONFIGS, 
    INITIAL_PROMPTS,
    MODELS_TO_RUN,
    get_project_config,
    get_experiment_config,
)
from benchmark_monitor import get_optimization_monitor

logger = logging.getLogger(__name__)
console = Console(
    width=120,
    style=Style(color="white"),
    highlight=True,
    soft_wrap=True,
)

# TODO: Move to opik_optimizer.utils
STYLES = {
    "header": Style(color="cyan", bold=True),
    "success": Style(color="green", bold=True),
    "warning": Style(color="yellow", bold=True),
    "error": Style(color="red", bold=True),
    "info": Style(color="blue"),
    "dim": Style(dim=True),
}
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
# TODO: Move to benchmark_utils module
def custom_json_serializer(obj: Any) -> Any:
    """Custom JSON serializer to handle specific object types."""
    if isinstance(obj, BaseMetric):
        # For metric objects, return their string representation or a more detailed dict if available
        # For simplicity, str(obj) often gives a good summary (e.g., class name)
        return str(obj) 
    # For datetime objects not handled by pydantic model_dump
    if hasattr(obj, 'isoformat'):
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
# TODO: Move to opik_optimizer.utils
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
    def __init__(
            self,
            output_dir: str = "benchmark_results",
            max_workers: int = 2,
            seed: int = 42,
            test_mode: bool = False,
            resume_enabled: bool = False,
            retry_failed_run_id: Optional[str] = None\
        ):
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
        # TODO: Move to opik_optimizer.utils and use across all benchmarks as opik_utils.set_global_random_seed()
        import random
        import numpy as np
        random.seed(seed)
        np.random.seed(seed)
        logger.info(f"Global random seed set to [bold cyan]{seed}[/bold cyan]")
        
        # Initialize shared cache
        initialize_cache()
        logger.info("[green]Shared cache initialized.[/green]")
        
        # Checkpoint handling
        self.checkpoint_dir = self.output_dir / "checkpoints"
        self.checkpoint_dir.mkdir(exist_ok=True)
        logger.info(f"Checkpoint directory created/ensured at [blue]{self.checkpoint_dir}[/blue]")
        self.load_latest_checkpoint()

    # TODO: Move to a dataset_utils module
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
                console.print(status_msg)
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
                    console.print(f"  {msg}")
            final_status_msg = "[bold green]Dataset Caching & Loading Complete![/bold green]"
            status.update(status=final_status_msg)
        console.print(Rule(style="dim blue"))

    # TODO: Move to a benchmark_utils module
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
                self.current_run_id = None
                self.task_results_dir = None
                self.resuming_run_active = False
                self.retry_failed_run_id = None
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
            self.resuming_run_active = False
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
                self.task_results_dir.mkdir(parents=True, exist_ok=True)
            else:
                self.task_results_dir = None
                logger.warning("[yellow]run_id and task_results_dir not found in checkpoint. Resuming might be problematic.[/yellow]")

        self.results = checkpoint_data.get("results_summary", [])
        self.test_mode = checkpoint_data.get("test_mode", self.test_mode)
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
        dataset_cache_keys = checkpoint_data.get("dataset_cache_keys", [])
        if dataset_cache_keys:
            logger.info("Restoring dataset cache...")
            for key in dataset_cache_keys:
                if key not in self.dataset_cache:
                    parts = key.rsplit('_', 1)
                    dataset_name_from_key = parts[0]
                    if dataset_name_from_key in DATASET_CONFIGS:
                        logger.info(f"  Re-caching dataset for key: {key} (name: {dataset_name_from_key})")
                        self.load_dataset(dataset_name_from_key, DATASET_CONFIGS[dataset_name_from_key]["huggingface_path"])
                    else:
                        logger.warning(f"  [yellow]Dataset config for '{dataset_name_from_key}' (from cache key '{key}') not found. Cannot re-cache.[/yellow]")
        
        # Restore environment details from checkpoint if needed (e.g., seed, max_workers)
        # These are typically set by args, but checkpoint can provide context of the saved run.
        env_details = checkpoint_data.get("environment", {})
        logger.info(f"Checkpoint loaded. Run ID: [bold yellow]{self.current_run_id}[/bold yellow]")
        logger.info(f"  Restored [cyan]{len(self.results)}[/cyan] task summaries from checkpoint.")
        logger.info(f"  Restored [cyan]{len(self.monitor.metrics_history)}[/cyan] metrics history entries.")
        logger.info(f"  Restored [cyan]{len(self.monitor.prompts_history)}[/cyan] prompts history entries.")
        logger.info(f"  Restored [cyan]{len(dataset_cache_keys)}[/cyan] dataset cache keys.")
        logger.info(f"  Original run seed: {env_details.get('seed')}, max_workers: {env_details.get('max_workers')}")
        
        if self.resume_enabled or self.retry_failed_run_id:
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
            "project_config": self.project_config,
            "results_summary": self.results,
            "dataset_cache_keys": list(self.dataset_cache.keys()),
            "monitor_state": {
                "metrics_history": self.monitor.metrics_history if hasattr(self.monitor, 'metrics_history') else [],
                "prompts_history": self.monitor.prompts_history if hasattr(self.monitor, 'prompts_history') else []
            },
            "environment": {
                "python_version": sys.version,
                "opik_optimizer_version": opik_optimizer.__version__,
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

    # TODO: Move to a dataset_utils module
    def load_dataset(
            self,
            dataset_name: str,
            huggingface_path: str
        ) -> Any:
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
                self.dataset_cache[cache_key] = dataset
                return dataset
            logger.info(f"[green]Successfully loaded {dataset_name}[/green] with [cyan]{len(items)}[/cyan] examples")
            self.dataset_cache[cache_key] = dataset
            return dataset
            
        except Exception as e:
            logger.error(f"[red]Error loading dataset {dataset_name}: {e}[/red]")
            logger.exception(f"Traceback for error loading dataset [bold]{dataset_name}[/bold]:")
            return None

    def create_optimizer(
            self,
            optimizer_config: Dict,
            model_name: str,
            project_name: str
        ) -> Any:
        """Create optimizer instance based on configuration."""
        try:
            optimizer_class_name = optimizer_config["class"]
            params = optimizer_config["params"].copy()
            params["model"] = model_name
            params["project_name"] = project_name
            params.pop("workspace", None)
            params["seed"] = self.seed
            params.setdefault("verbose", 1)
            
            # Adjust params for OpenAI reasoning models BEFORE init
            # TODO: Should be universal across all optimizers not just benchmarks
            if model_name.startswith("openai/o"):            
                logger.info(f"Adjusting params for OpenAI reasoning model ({model_name}) before init...")
                params["temperature"] = 1.0
                params["max_tokens"] = max(params.get("max_tokens", 0), 20000)
                logger.info(f"  Using temp={params['temperature']}, max_tokens={params['max_tokens']}")
            else:
                # Ensure defaults for non-reasoning models if not set
                params.setdefault("temperature", 0.1)
                params.setdefault("max_tokens", 5000)
            
            # Try direct optimizer class resolution first
            try:
                if optimizer_class_name == "EvolutionaryOptimizer":
                    from opik_optimizer import EvolutionaryOptimizer
                    optimizer_class = EvolutionaryOptimizer
                    logger.info("Using direct import for EvolutionaryOptimizer")
                else:
                    optimizer_class = globals()[optimizer_class_name]
            except Exception as e_class:
                logger.warning(f"Error in direct class resolution for {optimizer_class_name}: {e_class}")
                optimizer_class = globals()[optimizer_class_name]
                
            result = optimizer_class(**params)
            return result
        except Exception as e:
            logger.error(f"[red]Error creating optimizer {optimizer_config['class']} for model {model_name}: {e}[/red]")
            logger.exception(f"Traceback for error creating optimizer [bold]{optimizer_config['class']}[/bold]:")

            # Special handling for EvolutionaryOptimizer to help diagnose issues
            if optimizer_class_name == "EvolutionaryOptimizer":
                logger.error(f"[red]EvolutionaryOptimizer creation failed. Available keys: {list(globals().keys())}[/red]")
                try:
                    # Verify EvolutionaryOptimizer is imported properly
                    import opik_optimizer
                    from opik_optimizer import EvolutionaryOptimizer  # Try direct import
                    logger.info(f"EvolutionaryOptimizer direct import successful: {EvolutionaryOptimizer}")
                    # Try alternate initialization path
                    alternate_optimizer = EvolutionaryOptimizer(model=model_name, project_name=project_name, **params)
                    logger.info("[green]Alternate EvolutionaryOptimizer initialization successful[/green]")
                    return alternate_optimizer
                except Exception as e_alt:
                    logger.error(f"[red]Alternate EvolutionaryOptimizer initialization also failed: {e_alt}[/red]")
                    logger.exception("Traceback for alternate initialization attempt:")
            
            return None

    def run_optimization(
            self,
            dataset: Any,
            optimizer: Any,
            metrics: List[Any],
            initial_prompt: str,
            input_key: str,
            output_key: str,
            experiment_config: Dict
        ) -> Dict:
        """Run optimization for a single task."""
        if dataset is None or optimizer is None:
            logger.error("[bold red]Error: Dataset or optimizer is None[/bold red]")
            return None
        
        start_time_run_opt = time.time()
        # run_id should be self.current_run_id, which is set at the start of BenchmarkRunner.run_benchmark
        # task_id generation needs to be robust here if called outside the main loop context (though it typically isn't)
        # Ensure task_id is unique and well-formed
        model_name_for_task_id = "unknown_model"
        if hasattr(optimizer, 'model') and optimizer.model:
            model_name_for_task_id = optimizer.model.replace("/", "-") # Sanitize
        timestamp_for_task_id = datetime.now().strftime('%Y%m%d%H%M%S%f') # Added microseconds for more uniqueness
        task_id = f"{experiment_config['dataset']}-{experiment_config['optimizer']}-{model_name_for_task_id}-{timestamp_for_task_id}"

        # Task-specific File Logging Setup ---
        task_log_file_path = None
        file_handler = None
        root_logger = logging.getLogger() # Get the root logger
        original_root_level = root_logger.level

        # Target specific loggers for temporary un-suppression
        opik_logger = logging.getLogger("opik")
        fsbo_logger_name = "opik_optimizer.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer"
        mpo_logger_name = "opik_optimizer.meta_prompt_optimizer"
        fsbo_logger = logging.getLogger(fsbo_logger_name)
        mpo_logger = logging.getLogger(mpo_logger_name)
        tqdm_logger = logging.getLogger("tqdm")
        noisy_libs_for_task_log_control = ["LiteLLM", "urllib3", "requests", "httpx", "dspy", "datasets", "optuna", "filelock", "httpcore", "openai"] 
        controlled_noisy_loggers = {name: logging.getLogger(name) for name in noisy_libs_for_task_log_control}

        original_levels_and_propagate = {
            "opik": (opik_logger.level, opik_logger.propagate),
            fsbo_logger_name: (fsbo_logger.level, fsbo_logger.propagate),
            mpo_logger_name: (mpo_logger.level, mpo_logger.propagate),
            "tqdm": (tqdm_logger.level, tqdm_logger.propagate),
        }
        for name, logger_instance in controlled_noisy_loggers.items():
            original_levels_and_propagate[name] = (logger_instance.level, logger_instance.propagate)

        if hasattr(self, 'task_results_dir') and self.task_results_dir:
            self.task_results_dir.mkdir(parents=True, exist_ok=True)
            task_log_file_path = self.task_results_dir / f"{task_id}.log"
            try:
                file_handler = logging.FileHandler(task_log_file_path, mode='w')
                formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(processName)s - %(threadName)s - %(message)s')
                file_handler.setFormatter(formatter)
                
                # Root logger at DEBUG to catch everything for the file
                root_logger.addHandler(file_handler)
                root_logger.setLevel(logging.DEBUG) 

                # Temporarily adjust specific loggers for file output
                opik_logger.propagate = True
                opik_logger.setLevel(logging.INFO) 
                fsbo_logger.propagate = True
                fsbo_logger.setLevel(logging.INFO)
                mpo_logger.propagate = True
                mpo_logger.setLevel(logging.INFO)
                tqdm_logger.propagate = True
                tqdm_logger.setLevel(logging.INFO) 
                
                for name, logger_instance in controlled_noisy_loggers.items():
                    logger_instance.propagate = True
                    logger_instance.setLevel(logging.WARNING)
                
                logger.info(f"Task-specific logging for {task_id} directed to: {task_log_file_path} (opik/tqdm at INFO; noisy libs at WARNING for this file)")
            except Exception as e_log_setup:
                logger.error(f"[red]Failed to set up task-specific file logging for {task_id}: {e_log_setup}[/red]")
                file_handler = None
        else:
            logger.warning(f"[yellow]task_results_dir not available, skipping task-specific file logging for {task_id}[/yellow]")

        dataset_name = experiment_config["dataset"]
        optimizer_config_name_display = experiment_config["optimizer"]
        actual_optimizer_class_name = type(optimizer).__name__
        actual_optimizer_class_name_display = actual_optimizer_class_name.lower().replace("_", "-")
        model_name_display = optimizer.model

        # Initialize comprehensive result structure
        task_result = {
            "run_id": self.current_run_id,
            "task_id": task_id,
            "timestamp_start_task": datetime.now().isoformat(),
            "timestamp_end_task": None,
            "duration_seconds_task": None,
            "status": "failure",
            "error_message": None,
            "test_mode": self.test_mode,
            "seed": self.seed,
            "max_workers": self.max_workers,
            "project_name_opik": getattr(optimizer, 'project_name', None),
            "environment": {
                "python_version": sys.version,
                "opik_optimizer_version": opik_optimizer.__version__,
                # TODO: Add other key library versions if needed
            },
            "config": {
                "dataset_config_name": dataset_name,
                "dataset_huggingface_path": DATASET_CONFIGS[dataset_name]["huggingface_path"],
                "optimizer_config_name": experiment_config["optimizer"],
                "optimizer_class": actual_optimizer_class_name_display,
                "model_name": model_name_display,
                "optimizer_params": OPTIMIZER_CONFIGS[experiment_config["optimizer"]]["params"],
                "initial_prompt": initial_prompt,
                "input_key": input_key,
                "output_key": output_key,
            },
            "initial_evaluation": None,
            "optimization_process": None,
            "final_evaluation": None,
            "raw_optimizer_result": None,
        }
        
        # Store original class name for precise class checks later
        task_result["optimizer_original_class_name"] = actual_optimizer_class_name
        
        # Use the optimizer_config_name_display for the console print
        console.print(f"🏁 Starting task: [bold magenta]{task_id}[/bold magenta] ({dataset_name} / {optimizer_config_name_display} / {model_name_display})")
        logger.info(f"Starting optimization task: {task_id}...")

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
            
            # Prepare the objective MetricConfig, specializing for ContextRecall if it's the primary metric
            primary_metric_obj = metrics[0] # Assuming the first metric is the primary objective
            objective_metric_config_inputs = {}
            if isinstance(primary_metric_obj, ContextRecall):
                objective_metric_config_inputs = {
                    "input": from_dataset_field(name=input_key),      # e.g., 'article'
                    "output": from_llm_response_text(),               # Generated summary
                    "expected_output": from_dataset_field(name=output_key), # e.g., 'highlights'
                    "context": from_dataset_field(name=input_key)      # Source document, e.g., 'article'
                }
                logger.info(f"Primary objective is ContextRecall. Setting up specific MetricConfig inputs for OptimizationConfig.")
            else:
                # Default inputs for other primary metrics
                objective_metric_config_inputs = {
                    "input": from_dataset_field(name=input_key),
                    "output": from_llm_response_text(),
                    "reference": from_dataset_field(name=output_key),
                }

            config = OptimizationConfig(
                dataset=dataset,
                objective=MetricConfig(
                    metric=primary_metric_obj,
                    inputs=objective_metric_config_inputs
                ),
                task=TaskConfig(
                    instruction_prompt=initial_prompt,
                    input_dataset_fields=[input_key],
                    output_dataset_field=output_key,
                    use_chat_prompt=isinstance(optimizer, FewShotBayesianOptimizer),
                )
            )

            # Initial Prompt Evaluation --- 
            logger.debug("--> Evaluating initial prompt...")
            start_time_eval_initial = time.time()
            initial_scores = {}
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                future_to_metric = {}
                for metric in metrics:
                    # Create metric-specific config
                    metric_config_eval = None
                    if isinstance(metric, ContextPrecision):
                        metric_config_eval = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key), # 'article' for cnn_dailymail
                                "output": from_llm_response_text(),
                                "expected_output": from_dataset_field(name=output_key), # Use output_key ('highlights')
                                "context": from_dataset_field(name=input_key)    # Use input_key ('article')
                            },
                        )
                        logger.debug(f"Using ContextPrecision specific config for initial eval. input_key(metric input & context)='{input_key}', output_key(metric expected_output)='{output_key}'")
                    elif isinstance(metric, ContextRecall):
                        metric_config_eval = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key), # e.g., 'article' for cnn_dailymail
                                "output": from_llm_response_text(),           # The generated summary
                                "expected_output": from_dataset_field(name=output_key), # e.g., 'highlights' for cnn_dailymail
                                "context": from_dataset_field(name=input_key) # The source document, e.g., 'article' for cnn_dailymail
                            }
                        )
                        logger.debug(f"Using ContextRecall specific config for initial eval. input_key='{input_key}' (maps to metric's 'input' and 'context'), output_key='{output_key}' (maps to metric's 'expected_output').")
                    else:
                        # Default config for other metrics
                        metric_config_eval = MetricConfig(
                            metric=metric,
                            inputs={
                                "input": from_dataset_field(name=input_key),
                                "output": from_llm_response_text(),
                                "reference": from_dataset_field(name=output_key),
                            }
                        )
                        logger.debug(f"Using default metric config for {type(metric).__name__} for initial eval. input_key='{input_key}', output_key='{output_key}'.")
                    
                    prompt_for_eval = formatted_initial_prompt
                    # HACK: Add dummy user message for Anthropic if prompt is only a system message
                    # FIXME: This is a hack to work around the fact that Anthropic models don't support system messages in the same way other models do.
                    model_is_anthropic = "anthropic" in optimizer.model.lower()
                    if model_is_anthropic and isinstance(prompt_for_eval, list) and len(prompt_for_eval) == 1 and prompt_for_eval[0].get("role") == "system":
                        prompt_for_eval = prompt_for_eval + [{"role": "user", "content": "(Proceed based on system instructions)"}]
                        logger.warning(f"Applied Anthropic eval hack: Added dummy user message to initial system prompt.")

                    # Conditional evaluate_prompt call
                    if isinstance(optimizer, (MetaPromptOptimizer, MiproOptimizer)):
                        # Both MetaPrompt and Mipro need task_config now
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config_eval,
                            task_config=config.task, 
                            prompt=prompt_for_eval,
                            experiment_config=experiment_config
                        )
                    else:
                        # Default for FewShotBayesianOptimizer and others that might be added
                        future = executor.submit(
                            optimizer.evaluate_prompt,
                            dataset=dataset,
                            metric_config=metric_config_eval,
                            task_config=config.task,
                            prompt=prompt_for_eval,
                            experiment_config=experiment_config
                        )
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

            # Run Optimization
            n_trials_log = getattr(optimizer, 'n_trials', 'N/A')
            logger.info(f"--> Running optimization ({n_trials_log} trials)...")
            start_time_opt = time.time()
            results_obj = None
            raw_results_obj_for_saving = None

            try:
                if isinstance(optimizer, (MetaPromptOptimizer, MiproOptimizer)):
                    results_obj = optimizer.optimize_prompt(
                        dataset=config.dataset,
                        metric_config=config.objective,
                        task_config=config.task,
                        experiment_config=experiment_config
                    )
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
                # Check for EvolutionaryOptimizer by actual class name, not by lowercase displayed name
                elif "EvolutionaryOptimizer" in type(optimizer).__name__:
                    logger.info(f"Detected EvolutionaryOptimizer. Running with appropriate parameters.")
                    # Don't pass experiment_config to EvolutionaryOptimizer's optimize_prompt method
                    results_obj = optimizer.optimize_prompt(
                        dataset=config.dataset,
                        metric_config=config.objective,
                        task_config=config.task,
                        n_samples=getattr(optimizer, 'n_samples', 5)
                    )
                else:
                    logger.error(f"Unsupported optimizer: {actual_optimizer_class_name_display}")
                    task_result["error_message"] = f"Unsupported optimizer: {actual_optimizer_class_name_display}"
                    task_result["timestamp_end_task"] = datetime.now().isoformat()
                    task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                    return task_result
                
                # Store the raw result object before any processing, if serializable or convertible
                if results_obj:
                    # FIXME: This is a hack to work around the fact that Mipro's results_obj is not serializable.
                    # TODO: Move this to a separate function and call it for all optimizers.
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
                logger.error(f"[red]Error during {actual_optimizer_class_name_display}.optimize_prompt: {e}[/red]")
                logger.exception("Traceback:")
                task_result["error_message"] = f"Error in {actual_optimizer_class_name_display}.optimize_prompt: {str(e)}"
                task_result["timestamp_end_task"] = datetime.now().isoformat()
                task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                return task_result
            finally:
                # Task-specific File Logging Teardown
                if file_handler:
                    root_logger.removeHandler(file_handler)
                    file_handler.close()
                    logger.info(f"Closed task-specific log file: {task_log_file_path}")
                
                root_logger.setLevel(original_root_level)
                opik_level_orig, opik_prop_orig = original_levels_and_propagate["opik"]
                opik_logger.setLevel(opik_level_orig)
                opik_logger.propagate = opik_prop_orig
                fsbo_level_orig, fsbo_prop_orig = original_levels_and_propagate[fsbo_logger_name]
                fsbo_logger.setLevel(fsbo_level_orig)
                fsbo_logger.propagate = fsbo_prop_orig
                mpo_level_orig, mpo_prop_orig = original_levels_and_propagate[mpo_logger_name]
                mpo_logger.setLevel(mpo_level_orig)
                mpo_logger.propagate = mpo_prop_orig
                tqdm_level_orig, tqdm_prop_orig = original_levels_and_propagate["tqdm"]
                tqdm_logger.setLevel(tqdm_level_orig)
                tqdm_logger.propagate = tqdm_prop_orig
                for name, logger_instance in controlled_noisy_loggers.items():
                    level_orig, prop_orig = original_levels_and_propagate[name]
                    logger_instance.setLevel(level_orig)
                    logger_instance.propagate = prop_orig                
                logger.debug("Restored original logging levels/propagation for root and controlled loggers.")

            opt_time = time.time() - start_time_opt
            if results_obj is None:
                logger.error(f"[bold red]Optimization failed for {actual_optimizer_class_name_display}. results_obj is None.[/bold red]")
                task_result["error_message"] = f"Optimization failed for {actual_optimizer_class_name_display}, results_obj is None."
                task_result["timestamp_end_task"] = datetime.now().isoformat()
                task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                return task_result
                
            # Process optimization history for structured logging
            opt_history_processed = []
            global_iteration_count = 0 
            try:
                # Ensure case-insensitive comparison or match the actual derived string
                if actual_optimizer_class_name_display == "metapromptoptimizer": # Match the lowercase, hyphenated version
                    if hasattr(results_obj, 'details') and isinstance(results_obj.details, dict) and \
                       'rounds' in results_obj.details and results_obj.details['rounds'] and \
                       isinstance(results_obj.details['rounds'], list):
                        logger.debug(f"Processing {actual_optimizer_class_name_display} history from results_obj.details['rounds'] for task {task_id}")
                        # results_obj.details['rounds'] is a list of OptimizationRound objects
                        for round_obj in results_obj.details['rounds']:
                            round_number_val = round_obj.round_number # Access attribute
                            generated_prompts_list = round_obj.generated_prompts # Access attribute (should be a list of dicts)
                            
                            if isinstance(generated_prompts_list, list):
                                for i, candidate_prompt_data_dict in enumerate(generated_prompts_list):
                                    global_iteration_count += 1
                                    prompt_text = candidate_prompt_data_dict.get("prompt") # This is a dict
                                    score_value = candidate_prompt_data_dict.get("score")   # This is a dict
                                    
                                    objective_metric_name = results_obj.metric_name if hasattr(results_obj, 'metric_name') and results_obj.metric_name else 'objective_score'

                                    iter_detail = {
                                        "iteration": global_iteration_count, 
                                        "round_number": round_number_val,
                                        "candidate_in_round": i + 1,
                                        "timestamp": datetime.now().isoformat(), 
                                        "prompt_candidate": prompt_text,
                                        "parameters_used": None, 
                                        "scores": [],
                                        "tokens_used": None, 
                                        "cost": None, 
                                        "duration_seconds": None, 
                                    }
                                    if score_value is not None:
                                        iter_detail["scores"].append({
                                            "metric_name": str(objective_metric_name), 
                                            "score": score_value,
                                            "opik_evaluation_id": None 
                                        })
                                    opt_history_processed.append(iter_detail)
                            else:
                                logger.warning(f"'generated_prompts' attribute in round_obj for {actual_optimizer_class_name_display} task {task_id} is not a list. Found: {type(generated_prompts_list)}")
                    else:
                        details_type_msg = f"results_obj.details type: {type(results_obj.details)}" if hasattr(results_obj, 'details') else "results_obj has no 'details' attribute"
                        rounds_info_msg = ""
                        if hasattr(results_obj, 'details') and isinstance(results_obj.details, dict):
                            rounds_info_msg = f"'rounds' key in details: {'rounds' in results_obj.details}. Value of details['rounds']: {results_obj.details.get('rounds')}"
                        logger.warning(f"Condition failed for processing MetaPromptOptimizer/MiproOptimizer history for task {task_id}. {details_type_msg}. {rounds_info_msg}")
                
                elif actual_optimizer_class_name_display == "miprooptimizer": # NEW DEDICATED BLOCK FOR MIPRO
                    print(f"PRINT_DEBUG_HISTORY ({actual_optimizer_class_name_display}, {task_id}): Entered DEDICATED MiproOptimizer history processing block.")
                    logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): Entered DEDICATED MiproOptimizer history processing block.")
                    if hasattr(results_obj, "history") and results_obj.history is not None:
                        logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): results_obj.history exists and is not None. Type: {type(results_obj.history)}.")
                        if isinstance(results_obj.history, list):
                            logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): results_obj.history IS a list. Length: {len(results_obj.history)}.")
                            if results_obj.history:
                                opt_history_processed = results_obj.history
                                logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): Assigned results_obj.history to opt_history_processed. Length now: {len(opt_history_processed)}.")
                                if opt_history_processed and isinstance(opt_history_processed[0], dict):
                                    logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): First item of opt_history_processed: {list(opt_history_processed[0].keys())}")
                                elif not opt_history_processed:
                                    logger.warning(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): opt_history_processed is EMPTY after assignment. THIS IS UNEXPECTED.")
                            else:
                                logger.warning(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): results_obj.history IS AN EMPTY list. opt_history_processed will be empty.")
                                opt_history_processed = []
                        else:
                            logger.warning(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): results_obj.history is NOT a list. Type: {type(results_obj.history)}. opt_history_processed will be empty.")
                            opt_history_processed = []
                    else:
                        logger.warning(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): results_obj has NO 'history' attribute or it is None. opt_history_processed will be empty.")
                        opt_history_processed = []

                elif actual_optimizer_class_name_display == "fewshotbayesianoptimizer": # Existing block for FewShot, slightly adjusted
                    print(f"PRINT_DEBUG_HISTORY ({actual_optimizer_class_name_display}, {task_id}): Entered FewShotBayesianOptimizer history processing block.")
                    logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): Entered FewShotBayesianOptimizer history processing block.")
                    if hasattr(results_obj, "history") and results_obj.history is not None:
                        if isinstance(results_obj.history, list):
                            if results_obj.history:
                                opt_history_processed = results_obj.history
                            else: opt_history_processed = []
                        else: opt_history_processed = []
                    else: opt_history_processed = [] # Simplified for brevity, but should be the detailed logging

                # Check both the lowercase display name and the original class name for more robust detection
                elif actual_optimizer_class_name_display == "EvolutionaryOptimizer" or "EvolutionaryOptimizer" in task_result.get("optimizer_original_class_name", ""): # Add dedicated block for EvolutionaryOptimizer
                    logger.debug(f"HISTORY_DEBUG ({actual_optimizer_class_name_display}, {task_id}): Entered EvolutionaryOptimizer history processing block.")
                    
                    if hasattr(results_obj, "history") and results_obj.history is not None:
                        logger.debug(f"HISTORY_DEBUG: results_obj has history attribute, type: {type(results_obj.history)}")
                        
                        if isinstance(results_obj.history, list):
                            print(f"PRINT_DEBUG_HISTORY: results_obj.history is a list with {len(results_obj.history)} items")
                            logger.debug(f"HISTORY_DEBUG: results_obj.history is a list with {len(results_obj.history)} items")
                            
                            if results_obj.history:
                                # Try looking at the first history item structure
                                first_item = results_obj.history[0]
                                logger.debug(f"HISTORY_DEBUG: First history item keys: {list(first_item.keys()) if isinstance(first_item, dict) else 'Not a dict'}")
                                
                                opt_history_processed = results_obj.history
                                # Process the rounds from history into the expected format
                                for i, hist_item in enumerate(opt_history_processed):
                                    # Make sure each history item has the expected fields
                                    if "iteration" not in hist_item:
                                        hist_item["iteration"] = i + 1
                                    if "timestamp" not in hist_item:
                                        hist_item["timestamp"] = datetime.now().isoformat()
                                    # Ensure scores are in the right format
                                    if "scores" not in hist_item and "score" in hist_item:
                                        hist_item["scores"] = [{"metric_name": "objective_score", "score": hist_item["score"]}]
                                        
                                # Get LLM calls from last round or overall details if available
                                if results_obj.history and len(results_obj.history) > 0:
                                    last_round = results_obj.history[-1]
                                    if isinstance(last_round, dict):
                                        if "llm_calls" in last_round:
                                            task_result["optimization_process"]["llm_calls_total_optimization"] = last_round["llm_calls"]
                                
                                # Also try to get from details if it exists
                                if hasattr(results_obj, 'details') and isinstance(results_obj.details, dict):
                                    if "llm_calls" in results_obj.details:
                                        task_result["optimization_process"]["llm_calls_total_optimization"] = results_obj.details["llm_calls"]
                            else:
                                opt_history_processed = []
                        else:
                            opt_history_processed = []
                    else:
                        opt_history_processed = []

                else: # Fallback for other or unknown optimizer types
                    logger.debug(f"Processing history with fallback logic for {actual_optimizer_class_name_display} task {task_id}")
                    raw_history_fallback = []
                    if hasattr(results_obj, "history") and results_obj.history and isinstance(results_obj.history, list):
                        raw_history_fallback = results_obj.history
                    elif isinstance(results_obj, dict) and "history" in results_obj and isinstance(results_obj["history"], list):
                        raw_history_fallback = results_obj["history"]
                    else:
                        logger.warning(f"No 'history' list found in results_obj for fallback processing of {actual_optimizer_class_name_display} task {task_id}")

                    for i, hist_item_fallback in enumerate(raw_history_fallback):
                        global_iteration_count += 1
                        iter_detail = {
                            "iteration": global_iteration_count,
                            "timestamp": getattr(hist_item_fallback, "timestamp", datetime.now().isoformat()),
                            "prompt_candidate": getattr(hist_item_fallback, "prompt", None),
                            "parameters_used": getattr(hist_item_fallback, "parameters", None),
                            "scores": [],
                            "tokens_used": None, # TODO
                            "cost": None, # TODO
                            "duration_seconds": None, # TODO
                        }
                        current_score_val_fb = None
                        if isinstance(hist_item_fallback, dict):
                            current_score_val_fb = hist_item_fallback.get('score', hist_item_fallback.get('current_score'))
                            nested_scores_fb = hist_item_fallback.get("scores_per_metric", hist_item_fallback.get("metric_scores"))
                            if isinstance(nested_scores_fb, dict):
                                for m_name, m_score in nested_scores_fb.items():
                                    iter_detail["scores"].append({"metric_name": str(m_name), "score": m_score, "opik_evaluation_id": None}) # TODO
                            elif current_score_val_fb is not None:
                                iter_detail["scores"].append({"metric_name": "objective_score", "score": current_score_val_fb, "opik_evaluation_id": None}) # TODO
                        elif hasattr(hist_item_fallback, 'score'):
                            current_score_val_fb = hist_item_fallback.score
                            if hasattr(hist_item_fallback, 'scores_per_metric') and isinstance(hist_item_fallback.scores_per_metric, dict):
                                for m_name, m_score in hist_item_fallback.scores_per_metric.items():
                                    iter_detail["scores"].append({"metric_name": str(m_name), "score": m_score, "opik_evaluation_id": None}) # TODO
                            elif current_score_val_fb is not None:
                                iter_detail["scores"].append({"metric_name": "objective_score", "score": current_score_val_fb, "opik_evaluation_id": None}) # TODO
                        
                        if not iter_detail["scores"] and current_score_val_fb is not None:
                            iter_detail["scores"].append({"metric_name": "objective_score", "score": current_score_val_fb, "opik_evaluation_id": None}) # TODO
                        opt_history_processed.append(iter_detail)
            
            except Exception as e_hist_proc:
                logger.error(f"[red]Error processing optimization history for task {task_id} (optimizer: {actual_optimizer_class_name_display}): {e_hist_proc}[/red]")
                logger.exception("Traceback for history processing error:")

            task_result["optimization_process"] = {
                "timestamp_start": datetime.fromtimestamp(start_time_opt).isoformat(),
                "timestamp_end": datetime.now().isoformat(),
                "duration_seconds": opt_time,
                "optimizer_type": actual_optimizer_class_name_display,
                "num_trials_configured": getattr(optimizer, 'n_trials', getattr(optimizer, 'n_iterations', None)),
                "num_samples_configured": getattr(optimizer, 'n_samples', None),
                "best_score_achieved": getattr(results_obj, 'score', None), 
                "final_prompt": results_obj.details.get("chat_messages") if actual_optimizer_class_name_display == "fewshotbayesianoptimizer" and hasattr(results_obj, 'details') and isinstance(results_obj.details, dict) and "chat_messages" in results_obj.details else getattr(results_obj, 'prompt', None), 
                "history": opt_history_processed,
                "llm_calls_total_optimization": getattr(results_obj, 'llm_calls', None) # Extract llm_calls
            }

            # Calculate num_iter_log and print console message *after* task_result["optimization_process"] is populated
            num_iter_log = len(task_result.get("optimization_process", {}).get("history", []))
            
            best_score_val_for_log = None
            if results_obj: 
                best_score_val_for_log = getattr(results_obj, "score", None) 
                if best_score_val_for_log is None: 
                    best_score_val_for_log = getattr(results_obj, "best_score", None)
            
            best_score_log_str = f"{best_score_val_for_log:.4f}" if isinstance(best_score_val_for_log, (int, float)) else "[dim]N/A[/dim]"
            console.print(f"  Optimization done ({task_id}): Iterations={num_iter_log}, Best Internal Score (from optimizer)={best_score_log_str} ({opt_time:.2f}s)")

            # Final Prompt Evaluation 
            logger.debug("--> Entering Final Prompt Evaluation Stage...") 
            final_scores = {}
            final_eval_time = 0.0 
            final_prompt_to_eval = None 
            evaluation_errors = []
            start_time_eval_final = time.time()
            
            # Determine final_prompt_to_eval (representation of what the optimizer produced for logging)
            # Also determine actual_prompt_for_submission (what is actually sent to optimizer.evaluate_prompt)

            opt_details_for_final_prompt = getattr(results_obj, 'details', {}) if results_obj else {}
            actual_prompt_for_submission = None
            final_prompt_to_eval = None # Initialize

            # Initialize task_debug_notes in optimization_process dict
            if "optimization_process" not in task_result or task_result["optimization_process"] is None:
                task_result["optimization_process"] = {}
            task_result["optimization_process"]["task_debug_notes"] = None

            if actual_optimizer_class_name_display == "miprooptimizer":
                instr_mipro = getattr(results_obj, 'prompt', "[MIPRO Program - Instruction N/A]")
                final_prompt_to_eval = [{"role": "system", "content": str(instr_mipro)}]
                actual_prompt_for_submission = opt_details_for_final_prompt.get("program") 
                if actual_prompt_for_submission is None:
                     logger.error("[red]MiproOptimizer: DSPy program object not found for final evaluation. Cannot evaluate.[/red]")
                     evaluation_errors.append("MiproOptimizer: DSPy program not found for final eval.")
            elif actual_optimizer_class_name_display == "fewshotbayesianoptimizer":
                chat_messages_for_final_eval = None 
                
                if results_obj and hasattr(results_obj, 'details') and isinstance(results_obj.details, dict):
                    opt_details = results_obj.details
                    try:
                        retrieved_chat_messages = opt_details.get("chat_messages") 
                        if isinstance(retrieved_chat_messages, list) and len(retrieved_chat_messages) > 0:
                            chat_messages_for_final_eval = retrieved_chat_messages
                    except Exception as e_detail_access:
                        pass # Keep silent on error, fallback will handle

                if chat_messages_for_final_eval: 
                    final_prompt_to_eval = copy.deepcopy(chat_messages_for_final_eval)
                    actual_prompt_for_submission = copy.deepcopy(chat_messages_for_final_eval)
                else:
                    base_instr_fsbo = str(results_obj.prompt if hasattr(results_obj, 'prompt') else "Error: FSBO base instruction missing")
                    final_prompt_to_eval = [{"role": "system", "content": base_instr_fsbo}]
                    actual_prompt_for_submission = final_prompt_to_eval 
                    evaluation_errors.append("FSBO: Optimized chat_messages not retrieved for final eval; used base prompt.")
            
            elif actual_optimizer_class_name_display == "EvolutionaryOptimizer": # Specific handling for EvolutionaryOptimizer
                # Get prompt from multiple possible sources
                prompt_text = None
                
                # First, try direct attributes of the results_obj
                if hasattr(results_obj, 'prompt') and results_obj.prompt is not None:
                    prompt_text = results_obj.prompt
                # If not found, try .best_prompt direct attribute
                elif hasattr(results_obj, 'best_prompt') and results_obj.best_prompt is not None:
                    prompt_text = results_obj.best_prompt
                # If not found, check details dictionary
                elif hasattr(results_obj, 'details') and isinstance(results_obj.details, dict):
                    # Try standard entries in details
                    for key in ['final_prompt', 'best_prompt', 'prompt', 'final_prompt_representative']:
                        if key in results_obj.details and results_obj.details[key] is not None:
                            prompt_text = results_obj.details[key]
                            break
                
                # If still not found, try looking in history for best prompt
                if prompt_text is None and hasattr(results_obj, 'history') and isinstance(results_obj.history, list) and results_obj.history:
                    # Try to get the latest history entry
                    latest_history = results_obj.history[-1]
                    if isinstance(latest_history, dict):
                        # Check history fields
                        for key in ['best_prompt', 'current_prompt']:
                            if key in latest_history and latest_history[key] is not None:
                                prompt_text = latest_history[key]
                                break
                
                if prompt_text is not None:
                    if isinstance(prompt_text, str):
                        final_prompt_to_eval = [{"role": "system", "content": prompt_text}]
                        actual_prompt_for_submission = prompt_text
                    else:
                        final_prompt_to_eval = prompt_text
                        actual_prompt_for_submission = prompt_text
                else:
                    final_prompt_to_eval = [{"role": "system", "content": "Error: EvolutionaryOptimizer prompt was None for final eval."}]
                    actual_prompt_for_submission = None
                    logger.error("[red]EvolutionaryOptimizer: Prompt not found in results_obj for final evaluation. Cannot evaluate.[/red]")
                    evaluation_errors.append("EvolutionaryOptimizer: Prompt not found for final eval.")
                
            else: # MetaPromptOptimizer and other fallbacks
                string_prompt_val = getattr(results_obj, 'prompt', None) 
                actual_prompt_for_submission = string_prompt_val
                if isinstance(string_prompt_val, str):
                    final_prompt_to_eval = [{"role": "system", "content": string_prompt_val}]
                elif string_prompt_val is not None: 
                    final_prompt_to_eval = string_prompt_val
                else:
                    final_prompt_to_eval = [{"role": "system", "content": "Error: Optimizer prompt was None for final eval logging."}]
                    if actual_prompt_for_submission is None: # Only log error if submission also fails
                        logger.error(f"[red]{actual_optimizer_class_name_display}: Prompt not found in results_obj for final evaluation. Cannot evaluate.[/red]")
                        evaluation_errors.append(f"{actual_optimizer_class_name_display}: Prompt not found for final eval.")

            task_result["final_prompt"] = final_prompt_to_eval

            with ThreadPoolExecutor(max_workers=self.max_workers) as executor_final_eval:
                future_to_metric_final = {}
                
                # Determine the actual prompt structure to USE for evaluation based on optimizer type
                actual_prompt_for_submission = None
                if actual_optimizer_class_name_display == "MiproOptimizer":
                    actual_prompt_for_submission = opt_details_for_final_prompt.get("program") # DSPy program
                    if actual_prompt_for_submission is None:
                         logger.error("[red]MiproOptimizer: DSPy program object not found for final evaluation. Cannot evaluate.[/red]")
                         evaluation_errors.append("MiproOptimizer: DSPy program not found for final eval.")
                elif actual_optimizer_class_name_display.lower() == "fewshotbayesianoptimizer".lower() or "FewShotBayesianOptimizer" in task_result.get("optimizer_original_class_name", ""):
                    actual_prompt_for_submission = opt_details_for_final_prompt.get("chat_messages") # List of chat messages
                    if not actual_prompt_for_submission and opt_details_for_final_prompt.get("prompt_parameter") and hasattr(opt_details_for_final_prompt.get("prompt_parameter"), "as_template"):
                         actual_prompt_for_submission = opt_details_for_final_prompt.get("prompt_parameter").as_template().format()
                    if actual_prompt_for_submission is None:
                        logger.error("[red]FewShotBayesianOptimizer: Chat messages not found for final evaluation. Cannot evaluate.[/red]")
                        evaluation_errors.append("FewShotBayesianOptimizer: Chat messages not found for final eval.")
                elif actual_optimizer_class_name_display.lower() == "EvolutionaryOptimizer".lower() or "EvolutionaryOptimizer" in task_result.get("optimizer_original_class_name", ""):
                    # Since we already set actual_prompt_for_submission above, we don't need to duplicate the logic here
                    # But we do need to check if it's None for error handling consistency
                    if actual_prompt_for_submission is None:
                        # One more attempt to get the prompt
                        if hasattr(results_obj, 'history') and isinstance(results_obj.history, list) and results_obj.history:
                            latest_entry = results_obj.history[-1]
                            if isinstance(latest_entry, dict) and 'best_prompt' in latest_entry:
                                actual_prompt_for_submission = latest_entry['best_prompt']
                        
                        if actual_prompt_for_submission is None:
                            logger.error("[red]EvolutionaryOptimizer: Prompt not found for final evaluation (from previous handling). Cannot evaluate.[/red]")
                            evaluation_errors.append("EvolutionaryOptimizer: Prompt not found for final eval.")
                    else:
                        print(f"DEBUG: EvolutionaryOptimizer actual_prompt_for_submission is set: {actual_prompt_for_submission[:50]}...")
                else: # MetaPromptOptimizer and other fallbacks
                    actual_prompt_for_submission = getattr(results_obj, 'prompt', None) # Usually a string
                    if actual_prompt_for_submission is None:
                        logger.error(f"[red]{actual_optimizer_class_name_display}: Prompt not found in results_obj for final evaluation. Cannot evaluate.[/red]")
                        evaluation_errors.append(f"{actual_optimizer_class_name_display}: Prompt not found for final eval.")

                if actual_prompt_for_submission is not None:
                    # Apply Anthropic hack if needed (only to the submission variant)
                    prompt_to_submit_anthropic_hacked = actual_prompt_for_submission
                    current_prompt_display_for_anthropic_hack = final_prompt_to_eval # Use the display version for checking
                    if isinstance(current_prompt_display_for_anthropic_hack, list): # Ensure it's list before indexing
                        model_is_anthropic = "anthropic" in optimizer.model.lower()
                        if model_is_anthropic and len(current_prompt_display_for_anthropic_hack) == 1 and current_prompt_display_for_anthropic_hack[0].get("role") == "system":
                            if isinstance(actual_prompt_for_submission, list): # Make sure we are adding to a list
                                prompt_to_submit_anthropic_hacked = actual_prompt_for_submission + [{"role": "user", "content": "(Proceed based on system instructions)"}]
                                logger.warning(f"Applied Anthropic eval hack to final prompt for {actual_optimizer_class_name_display}.")
                        elif model_is_anthropic and len(current_prompt_display_for_anthropic_hack) > 1 and current_prompt_display_for_anthropic_hack[-1].get("role") == "assistant":
                             if isinstance(actual_prompt_for_submission, list): # Make sure we are adding to a list
                                prompt_to_submit_anthropic_hacked = actual_prompt_for_submission + [{"role": "user", "content": "(Proceed based on provided examples and system instructions)"}]
                                logger.warning(f"Applied Anthropic eval hack (few-shot case) to final prompt for {actual_optimizer_class_name_display}.")
                    
                    if not metrics: 
                        logger.warning(f"FINAL EVAL ({actual_optimizer_class_name_display}): Metrics list is empty. No final evaluation tasks to submit.")
                    else:
                        for metric_final_obj in metrics:
                            metric_config_final_eval = None
                            if isinstance(metric_final_obj, ContextPrecision):
                                metric_config_final_eval = MetricConfig(
                                    metric=metric_final_obj,
                                    inputs={
                                        "input": from_dataset_field(name=input_key), # This is 'article' for cnn_dailymail
                                        "output": from_llm_response_text(),
                                        "expected_output": from_dataset_field(name=output_key), # Map to 'highlights'
                                        "context": from_dataset_field(name=input_key)    # Map to 'article'
                                    }
                                )
                                logger.debug(f"Using ContextPrecision specific config for final eval. input_key(metric input & context)='{input_key}', output_key(metric expected_output)='{output_key}'")
                            elif isinstance(metric_final_obj, ContextRecall):
                                metric_config_final_eval = MetricConfig(
                                    metric=metric_final_obj,
                                    inputs={
                                        "input": from_dataset_field(name=input_key),      # e.g., 'article'
                                        "output": from_llm_response_text(),               # Generated summary
                                        "expected_output": from_dataset_field(name=output_key), # e.g., 'highlights'
                                        "context": from_dataset_field(name=input_key)      # Source document, e.g., 'article'
                                    }
                                )
                                logger.debug(f"Using ContextRecall specific config for final eval. input_key='{input_key}' (maps to metric's 'input' and 'context'), output_key='{output_key}' (maps to metric's 'expected_output').")
                            else: 
                                metric_config_final_eval = MetricConfig(
                                    metric=metric_final_obj,
                                    inputs={
                                        "input": from_dataset_field(name=input_key), 
                                        "output": from_llm_response_text(), 
                                        "reference": from_dataset_field(name=output_key)
                                    }
                                )
                                logger.debug(f"Using default metric config for {type(metric_final_obj).__name__} for final eval. input_key='{input_key}', output_key='{output_key}'.")
                            
                            eval_n_samples_final = 5 if self.test_mode else None # Use a small sample for final eval in test mode too
                            
                            # Submit the evaluation task
                            current_task_config_for_final_eval = config.task
                            if actual_optimizer_class_name_display == "MiproOptimizer":
                                # Mipro's evaluate_prompt might not need a task_config if the prompt is the program itself
                                # However, our BaseOptimizer.evaluate_prompt signature has it, so pass the original.
                                pass


                            if current_task_config_for_final_eval is None and not isinstance(optimizer, MiproOptimizer): # Mipro is a special case
                                logger.error(f"[bold red]CRITICAL ERROR: task_config is None before submit for final eval ({actual_optimizer_class_name_display}). Skipping this metric: {metric_final_obj}[/bold red]")
                                evaluation_errors.append(f"Task config was None for final eval of metric {metric_final_obj}")
                                continue
                            
                            try:
                                future = executor_final_eval.submit(
                                    optimizer.evaluate_prompt, 
                                    dataset=dataset, 
                                    metric_config=metric_config_final_eval, 
                                    task_config=current_task_config_for_final_eval, # Pass task_config
                                    prompt=prompt_to_submit_anthropic_hacked, # Use the (potentially hacked) actual submission prompt
                                    experiment_config=experiment_config, 
                                    n_samples=eval_n_samples_final
                                )
                                future_to_metric_final[future] = metric_final_obj
                            except Exception as e_submit_final:
                                err_msg_submit = f"Error submitting final eval for {metric_final_obj} ({actual_optimizer_class_name_display}): {e_submit_final}"
                                logger.error(f"[bold red]CRITICAL ERROR DURING FINAL EVAL SUBMISSION: {err_msg_submit}[/bold red]")
                                logger.exception("Traceback for final eval submission error:")
                                evaluation_errors.append(err_msg_submit)
                else:
                    logger.error(f"[red]FINAL EVAL SKIPPED for {actual_optimizer_class_name_display}: actual_prompt_for_submission was None.[/red]")
                    if not evaluation_errors: # Add a generic error if none more specific was added
                        evaluation_errors.append(f"{actual_optimizer_class_name_display}: No valid prompt/program for final evaluation.")

                logger.debug(f"FINAL EVAL ({actual_optimizer_class_name_display}): All {len(future_to_metric_final)} final eval futures submitted. Now processing results.")
                for future in as_completed(future_to_metric_final):
                    metric_obj_final = future_to_metric_final[future]
                    try:
                        final_scores[str(metric_obj_final)] = future.result()
                    except Exception as e_final_eval: 
                        final_scores[str(metric_obj_final)] = None
                        logger.critical(f"CRITICAL_DEBUG: Exception during final_scores future.result() for {metric_obj_final} ({actual_optimizer_class_name_display})")
                        logger.critical(f"  Exception type: {type(e_final_eval)}")
                        logger.critical(f"  Exception args: {e_final_eval.args}")
                        logger.critical(f"  Exception str: {str(e_final_eval)}")
                        tb_str_final_eval = traceback.format_exc()
                        console.print(f"[bold red]RAW TRACEBACK from future.result() in final eval ({metric_obj_final} for {actual_optimizer_class_name_display}):[/bold red]\n{tb_str_final_eval}")
                        logger.exception("  Full Traceback for final eval future.result() error (logged):") 

                        exc_type_name = type(e_final_eval).__name__
                        exc_str = str(e_final_eval)
                        exc_args_str = ""
                        try: exc_args_str = str(e_final_eval.args)
                        except Exception: exc_args_str = "(unable to stringify args)"
                        err_msg_detail = exc_str
                        if not exc_str or exc_str.strip().lower() == "none": 
                            err_msg_detail = f"Exception of type {exc_type_name} occurred"
                            if e_final_eval.args: err_msg_detail += f" with args: {exc_args_str}"
                        err_msg = f"Final eval err ({metric_obj_final} for {actual_optimizer_class_name_display}): {err_msg_detail}"
                        logger.error(f"[red]{err_msg}[/red]")
                        evaluation_errors.append(err_msg)

            # final_eval_time calculation moved here, after the ThreadPoolExecutor block
            if future_to_metric_final or (not metrics and prompt_for_actual_eval is not None) : 
                final_eval_time = time.time() - start_time_eval_final
            
            final_scores_str = ", ".join([f"{k}: {v:.4f}" if isinstance(v, (int, float)) else f"{k}: N/A" for k, v in final_scores.items()]) 
            logger.info(f"  Final eval ({task_id}): {final_scores_str} ({final_eval_time:.2f}s)")

            # Store final evaluation results properly
            task_result["final_evaluation"] = {
                "metrics": [
                    {
                        "metric_name": str(metric_key), 
                        "score": score_val,
                        "timestamp": datetime.now().isoformat()
                    }
                    for metric_key, score_val in final_scores.items() 
                ],
                "duration_seconds": final_eval_time, 
                "prompt_used": final_prompt_to_eval
            }

            task_result["final_prompt"] = task_result["optimization_process"]["final_prompt"]
            if evaluation_errors:
                 task_result["error_message"] = "; ".join(evaluation_errors)
                 task_result["status"] = "failure_in_evaluation"
            else:
                 task_result["status"] = "success"

            # Package Results
            total_run_time_task = time.time() - start_time_run_opt
            task_result["timestamp_end_task"] = datetime.now().isoformat()
            task_result["duration_seconds_task"] = total_run_time_task
            if task_result["status"] == "success":
                console.print(f"[green]✓ Completed task: [bold magenta]{task_id}[/bold magenta] in {total_run_time_task:.2f}s[/green]")
            else:
                console.print(f"[red]✗ Failed task: [bold magenta]{task_id}[/bold magenta] in {total_run_time_task:.2f}s (Status: {task_result['status']})[/red]")
            return task_result
        
        except Exception as e:
            logger.error(f"[red]Unexpected error in run_opt for {dataset_name}/{actual_optimizer_class_name_display} (task: {task_id}): {e}[/red]")
            logger.exception("Traceback:")
            task_result["error_message"] = f"Outer exception in run_optimization: {str(e)} - Traceback: {traceback.format_exc()}"
            task_result["status"] = "failure"
            task_result["timestamp_end_task"] = datetime.now().isoformat()
            task_result["duration_seconds_task"] = time.time() - start_time_run_opt
            return task_result

    def run_benchmark(
            self,
            datasets: List[str] = None,
            optimizers: List[str] = None
        ):
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
            pass # Initializing successful_tasks and failed_tasks to 0 is correct for a new run/retry session.

        completed_results_display = []
        active_tasks_status = {} # {future: {"desc": str, "optimizer_key": str, "model": str}}

        # Setup Live display components
        progress = Progress(*PROGRESS_COLUMNS, console=console, transient=False, expand=True)
        
        # Determine actual number of tasks that will be submitted for progress bar total
        tasks_to_plan_for_progress = []
        for ds_key_prog in active_datasets_to_run:
            for opt_key_prog in optimizers:
                for model_name_prog in MODELS_TO_RUN: # Added model loop
                    tasks_to_plan_for_progress.append((ds_key_prog, opt_key_prog, model_name_prog))
        
        num_tasks_for_progress_bar = 0
        run_id_to_check_for_resume = self.current_run_id_for_resume_logic if hasattr(self, 'current_run_id_for_resume_logic') and self.resuming_run_active else None

        if self.retry_failed_run_id and self.resuming_run_active and self.results and run_id_to_check_for_resume:
            logger.info(f"Retry mode: Filtering tasks based on previous run_id: {run_id_to_check_for_resume}")
            tasks_to_actually_retry_count = 0
            for task_to_plan_tuple in tasks_to_plan_for_progress:
                found_in_results = False
                failed_in_target_run = False
                for res_summary_prog in self.results:
                    if res_summary_prog.get("run_id") == run_id_to_check_for_resume: 
                        task_key_prog_sum = (res_summary_prog.get("dataset"), res_summary_prog.get("optimizer"), res_summary_prog.get("model_name_used"))
                        if task_key_prog_sum == task_to_plan_tuple:
                            found_in_results = True
                            if res_summary_prog.get("status") != "success":
                                failed_in_target_run = True
                            break # Found the task in the target run results
                # Task wasn't in the specified run_id's results at all (e.g. new combo added)
                if not found_in_results:
                    logger.info(f"  Task {task_to_plan_tuple} not found in results of run {run_id_to_check_for_resume}, will run as new.")
                    tasks_to_actually_retry_count += 1
                elif failed_in_target_run:
                    logger.info(f"  Task {task_to_plan_tuple} FAILED in run {run_id_to_check_for_resume}, will be retried.")
                    tasks_to_actually_retry_count += 1
                # Successfully completed in target run
                else:
                    logger.info(f"  Task {task_to_plan_tuple} was SUCCESSFUL in run {run_id_to_check_for_resume}, will be skipped.")
            num_tasks_for_progress_bar = tasks_to_actually_retry_count
            logger.info(f"Retry mode: Progress bar total set to {num_tasks_for_progress_bar} (tasks from run '{run_id_to_check_for_resume}' to retry or run as new).")
        
        elif self.resuming_run_active and self.results and run_id_to_check_for_resume:
            logger.info(f"Resume mode: Filtering tasks based on previous run_id: {run_id_to_check_for_resume}")
            completed_keys_for_resume_prog = set()
            for res_summary_prog in self.results:
                # Only consider results from the specific run_id we are resuming from
                if res_summary_prog.get("run_id") == run_id_to_check_for_resume and res_summary_prog.get("status") == "success":
                    completed_keys_for_resume_prog.add((res_summary_prog.get("dataset"), res_summary_prog.get("optimizer"), res_summary_prog.get("model_name_used")))
            
            num_tasks_to_run_after_skipping = 0
            for task_to_plan_tuple in tasks_to_plan_for_progress:
                if task_to_plan_tuple not in completed_keys_for_resume_prog:
                    num_tasks_to_run_after_skipping +=1
                else:
                    logger.info(f"  Task {task_to_plan_tuple} was SUCCESSFUL in run {run_id_to_check_for_resume}, will be skipped.")
            num_tasks_for_progress_bar = num_tasks_to_run_after_skipping
            logger.info(f"Resume mode: Progress bar total set to {num_tasks_for_progress_bar} (total tasks planned - previously successful in run '{run_id_to_check_for_resume}').")
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
                opt_key = status_info.get("optimizer_key", "?") # Use optimizer_key
                model_original = status_info.get("model", "?") # Use the original model name stored in status_info["model"]
                # Extract dataset from desc (first part before '/')
                # TODO: Move this to a function
                try:
                    dataset_part = desc.split('/')[0].replace("Running: ", "").strip()
                    # Use opt_key (config name) in display
                    display_text = f" • {dataset_part} + {model_original}" 
                except Exception:
                    display_text = f" • {desc}" # Fallback to full desc
                
                active_list.append(
                     Text.assemble((display_text, "yellow"), (f" [{opt_key}]", "dim")) # Use opt_key
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
                # Submission Loop 
                tasks_to_plan = []
                for ds_key in active_datasets_to_run:
                    for opt_key in optimizers:
                        for model_to_run in MODELS_TO_RUN:
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

                # Prepare a set of successfully completed task configurations if resuming
                completed_task_keys_from_checkpoint = set()
                # Also, if retrying a specific failed run, identify tasks that actually failed
                tasks_to_explicitly_retry_keys = set() 
                run_id_to_use_for_skipping_logic = None

                if self.resuming_run_active and self.results:
                    run_id_to_use_for_skipping_logic = self.current_run_id_for_resume_logic if hasattr(self, 'current_run_id_for_resume_logic') else None
                    logger.info(f"Resume/Retry active. Using run_id '{run_id_to_use_for_skipping_logic}' for task skipping/retrying logic.")

                    for res_summary in self.results:
                        # Only consider results from the specific run_id we are resuming/retrying from
                        if res_summary.get("run_id") != run_id_to_use_for_skipping_logic:
                            continue

                        if res_summary.get("dataset") and res_summary.get("optimizer") and res_summary.get("model_name_used"):
                            task_key_from_sum = (
                                res_summary["dataset"],
                                res_summary["optimizer"],
                                res_summary["model_name_used"]
                            )
                            if res_summary.get("status") == "success":
                                completed_task_keys_from_checkpoint.add(task_key_from_sum)
                            elif self.retry_failed_run_id: # If retrying, add failed tasks to the retry set
                                tasks_to_explicitly_retry_keys.add(task_key_from_sum)
                
                logger.info(f"Tasks marked as successfully completed in prior run ({run_id_to_use_for_skipping_logic}): {len(completed_task_keys_from_checkpoint)}")
                if self.retry_failed_run_id:
                    logger.info(f"Tasks marked for explicit retry from prior run ({run_id_to_use_for_skipping_logic}): {len(tasks_to_explicitly_retry_keys)}")

                for dataset_key, optimizer_key, model_name, sanitized_model_name_for_ids, optimizer_config_for_current_task in tasks_to_plan:
                    task_config_tuple_for_check = (dataset_key, optimizer_key, model_name)

                    # Resume/Retry Logic:
                    if self.resuming_run_active: 
                        if self.retry_failed_run_id:
                            # If retrying a specific run, we ONLY run tasks that FAILED in that run OR were not present.
                            # A task is skipped if it was successful in that specific run.
                            if task_config_tuple_for_check in completed_task_keys_from_checkpoint:
                                logger.info(f"RETRY MODE: Skipping already successfully completed task from run '{run_id_to_use_for_skipping_logic}': {task_config_tuple_for_check}")
                                # Don't advance progress here if it wasn't counted in num_tasks_for_progress_bar
                                # The num_tasks_for_progress_bar logic for retry should already exclude these.
                                continue
                            # If it's in tasks_to_explicitly_retry_keys, it failed, so we run it.
                            # If it's not in completed_task_keys_from_checkpoint AND not in tasks_to_explicitly_retry_keys,
                            # it means it wasn't in the target run at all, so we run it as a new task.
                            logger.info(f"RETRY MODE: Task {task_config_tuple_for_check} will be run (either failed previously or is new).")
                        else: # Normal resume (not a specific retry_failed_run_id)
                            if task_config_tuple_for_check in completed_task_keys_from_checkpoint:
                                logger.info(f"RESUME MODE: Skipping already successfully completed task from run '{run_id_to_use_for_skipping_logic}': {task_config_tuple_for_check}")
                                # Don't advance progress here if it wasn't counted for the progress bar
                                continue

                    # If not resuming/retrying or if the task needs to be run based on above logic:
                    task_desc_short = f"{dataset_key}/{optimizer_key}/{sanitized_model_name_for_ids}"
                    dataset_obj = self.dataset_cache[f"{dataset_key}_{self.test_mode}"]
                    project_name_opik = f"benchmark-{self.current_run_id}-{dataset_key}-{optimizer_key}-{sanitized_model_name_for_ids}-{datetime.now().strftime('%Y%m%d%H%M%S')}"
                    optimizer_instance = self.create_optimizer(optimizer_config_for_current_task, model_name, project_name_opik)
                    if optimizer_instance is None:
                        logger.error(f"[red]✗ Failed create optimizer {optimizer_key}/{dataset_key} for model {model_name}. Skip.[/red]")
                        failed_tasks += 1
                        progress.update(overall_progress_task, advance=1)
                        summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{total_tasks} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                        live.update(generate_live_display())
                        continue
                        
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
                    task_desc_short = f"{dataset_key}/{optimizer_key}/{sanitized_model_name_for_ids}"
                    future_to_meta[current_future] = {
                        "dataset_key": dataset_key, 
                        "optimizer_key": optimizer_key, 
                        "desc": task_desc_short,
                        "optimizer_name": type(optimizer_instance).__name__, # Keep actual class name if needed elsewhere
                        "model_name": model_name,
                        "sanitized_model_name": sanitized_model_name_for_ids
                    }
                    active_tasks_status[current_future] = {
                        "desc": f"Running: {task_desc_short}",
                        "optimizer_key": optimizer_key, # Store optimizer_key (config name)
                        "model": model_name
                    }
                    summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{num_tasks_for_progress_bar} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                    live.update(generate_live_display())

                # Processing Loop 
                for future_item in as_completed(future_to_meta.keys()):
                    meta = future_to_meta[future_item]
                    d_key, o_key = meta["dataset_key"], meta["optimizer_key"]
                    sanitized_model_name_for_ids = meta["sanitized_model_name"]
                    model_name_original = meta["model_name"]
                    task_desc_short = meta["desc"]
                    run_status_flag = "failure" 
                    result_data = None
                    task_id_for_log = "unknown_task"

                    logger.debug(f"Future {id(future_item)} (Meta: {meta}) completed. Attempting to remove from active_tasks_status (current size: {len(active_tasks_status)}). Keys: {list(active_tasks_status.keys())}")
                    if future_item in active_tasks_status:
                        popped_status_info = active_tasks_status.pop(future_item, None)
                        logger.debug(f"Popped {id(future_item)} (Meta: {meta}) from active_tasks_status. New size: {len(active_tasks_status)}. Popped info was valid: {popped_status_info is not None}")
                    else:
                        logger.warning(f"Future {id(future_item)} (Meta: {meta}) was NOT in active_tasks_status when trying to pop. active_tasks_status keys: {list(active_tasks_status.keys())}")
                    
                    try:
                        result_data = future_item.result() 
                        if result_data and isinstance(result_data, dict):
                            task_id_for_log = result_data.get("task_id", f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-no_task_id_in_res")
                            task_json_filename = f"{task_id_for_log}.json"
                            task_json_path = self.task_results_dir / task_json_filename
                            logger.debug(f"Constructed task JSON path: {task_json_path}")
                            try:
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
                                "model_name_used": model_name_original,
                                "final_primary_score": None, # TODO: Add this
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
                                "task_id": f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-invalid_data",
                                "run_id": self.current_run_id,
                                "dataset": d_key,
                                "optimizer": o_key,
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
                            "task_id": f"{d_key}-{o_key}-{sanitized_model_name_for_ids}-future_exception",
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
                        # opt_details_summary is now the full result_data for the task
                        # so create_result_panel can access everything it needs.
                        panel_creation_data = result_data 

                        # No specific opt_details_summary dict needed if passing full result_data
                        # However, create_result_panel expects an 'optimization_details' like structure
                        # Let's ensure what we pass to create_result_panel is consistent or adapt create_result_panel
                        # For now, we pass the whole result_data, create_result_panel will need to be adapted.

                        if not result_data: # If future errored and result_data is None
                            panel_creation_data = {
                                "config": {"dataset_config_name": d_key, "optimizer_config_name": o_key},
                                "duration_seconds_task": 0,
                                "optimization_process": {"error": f"Task failed during execution (check logs for {d_key}/{o_key})"},
                                "status": run_status_flag # which is failure here
                            }
                            # Ensure metrics_to_display, time_taken_display are appropriate for failure
                            metrics_to_display = {}
                            time_taken_display = 0
                        else:
                            # This block is for successful task completion where result_data is present
                            initial_eval_data = result_data.get("initial_evaluation") 
                            initial_metrics = initial_eval_data.get("metrics", []) if initial_eval_data is not None else []
                            final_eval_data = result_data.get("final_evaluation")
                            final_eval_metrics = final_eval_data.get("metrics", []) if final_eval_data is not None else []
                            
                            initial_scores_panel_data = {m.get("metric_name", "unk"): m.get("score") for m in initial_metrics if isinstance(m, dict)} 
                            final_scores_panel_data = {f"Final {m.get('metric_name', 'unk')}": m.get("score") for m in final_eval_metrics if isinstance(m, dict)}
                            metrics_to_display = {**initial_scores_panel_data, **final_scores_panel_data}
                            time_taken_display = result_data.get("duration_seconds_task", 0)
                            # panel_creation_data is already result_data

                        result_panel = create_result_panel(
                            display_dataset_name,
                            o_key, # Pass the optimizer_key (config name)
                            # Pass the full result_data which create_result_panel will parse
                            # This replaces metrics, time_taken, optimization_details, run_status as separate args
                            task_detail_data=panel_creation_data 
                        )
                        completed_results_display.append(result_panel) 
                        
                        progress.update(overall_progress_task, advance=1)
                        # Use num_tasks_for_progress_bar for the total in the summary line
                        summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{num_tasks_for_progress_bar} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                        live.update(generate_live_display())
                        
                        self.save_checkpoint() # Save checkpoint after each task (implicitly saves self.results)
                        self.save_results() # Save CSV summary incrementally

        # End of Live Block
        overall_duration = time.time() - overall_start_time
        print_benchmark_footer(self.results, successful_tasks, failed_tasks, overall_duration, completed_results_display)

    def save_results(self):
        """Save summary results to CSV. Detailed results are already saved as individual task JSONs."""
        if not self.results:
            logger.info("[yellow]No task metadata in self.results to generate CSV summary.[/yellow]")
            return
        # Ensure current_run_id and task_results_dir are available for naming/pathing the summary CSV
        if not hasattr(self, 'current_run_id') or not self.current_run_id or not hasattr(self, 'task_results_dir') or not self.task_results_dir:
             logger.warning("[yellow]Cannot save summary CSV: run_id or task_results_dir not set.[/yellow]")
             return
             
        run_id_for_filename = self.current_run_id 
        csv_filename = f"run_summary_{run_id_for_filename}.csv"
        csv_path_abs = (self.task_results_dir / csv_filename).resolve()
        logger.info(f"Generating/Updating summary CSV: [blue]{csv_path_abs}[/blue]")

        flat_data_for_csv = []
        for task_summary in self.results:
            # Default item data for CSV
            # TODO: Move this to a function or pydantic model
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
                if json_path:
                    try:
                        with open(json_path, "r") as f_detail:
                            detail_data = json.load(f_detail)
                        item_data["error_message"] = detail_data.get("error_message")
                    except Exception: 
                        item_data["error_message"] = "(Failed to read details from JSON)"
                else:
                     item_data["error_message"] = task_summary.get("error_message", "(No JSON path found)")
                flat_data_for_csv.append(item_data)
                # Skip detailed score processing for non-success
                continue

            try:
                with open(json_path, "r") as f_detail:
                    detail_data = json.load(f_detail)

                # Define current_task_config_data from detail_data for this task
                current_task_config_data = detail_data.get("config", {})
                # Update basic info just in case summary was incomplete
                # Use current_task_config_data here and throughout this block
                optimizer_params = current_task_config_data.get("optimizer_params", {})
                item_data["run_id"] = detail_data.get("run_id", item_data["run_id"])
                item_data["task_id"] = detail_data.get("task_id", item_data["task_id"])
                item_data["dataset"] = current_task_config_data.get("dataset_config_name", item_data["dataset"])
                item_data["optimizer"] = current_task_config_data.get("optimizer_config_name", item_data["optimizer"])
                item_data["model_name"] = current_task_config_data.get("model_name", item_data["model_name"])
                if not item_data["model_name"] or item_data["model_name"] == "N/A": 
                    item_data["model_name"] = optimizer_params.get("model", "N/A")
                item_data["status"] = detail_data.get("status", item_data["status"])
                item_data["duration_seconds_task"] = detail_data.get("duration_seconds_task", item_data["duration_seconds_task"])
                item_data["initial_prompt_template"] = current_task_config_data.get("initial_prompt")
                item_data["dataset_size"] = None # TODO: Add this

                initial_eval = detail_data.get("initial_evaluation", {})
                if initial_eval and isinstance(initial_eval.get("metrics"), list):
                    for metric_res in initial_eval["metrics"]:
                        # Use cleaned name for CSV headers
                        metric_name_cleaned = clean_metric_name(str(metric_res.get("metric_name", "unknown_metric")))
                        metric_name_for_header = metric_name_cleaned.replace(" ", "_").replace(".", "_") 
                        item_data[f"initial_{metric_name_for_header}_score"] = metric_res.get("score")

                final_eval = detail_data.get("final_evaluation", {})
                if final_eval and isinstance(final_eval.get("metrics"), list):
                    for metric_res in final_eval["metrics"]:
                        # Use cleaned name for CSV header key
                        metric_name_cleaned = clean_metric_name(str(metric_res.get("metric_name", "unknown_metric")))
                        metric_name_for_header = metric_name_cleaned.replace(" ", "_").replace(".", "_") 
                        item_data[f"final_{metric_name_for_header}_score"] = metric_res.get("score")
                
                # Optimization process details
                opt_process = detail_data.get("optimization_process", {})
                optimizer_class_from_detail = current_task_config_data.get("optimizer_class")
                if optimizer_class_from_detail == "FewShotBayesianOptimizer":
                    item_data["opt_num_iterations"] = opt_process.get("num_trials_configured")
                else:
                    # For MetaPromptOptimizer and others, use length of its processed history
                    item_data["opt_num_iterations"] = len(opt_process.get("history", [])) 
                
                item_data["opt_best_score_achieved"] = opt_process.get("best_score_achieved")
                item_data["opt_duration_seconds"] = opt_process.get("duration_seconds")
                temp_val = None
                if optimizer_class_from_detail == "FewShotBayesianOptimizer":
                    raw_opt_res = detail_data.get("raw_optimizer_result", {})
                    temp_val = raw_opt_res.get("details", {}).get("temperature")
                else:
                    exp_params = current_task_config_data.get("parameters", {})
                    if not exp_params: 
                         exp_params = current_task_config_data.get("optimizer_params", {})
                    temp_val = exp_params.get("temperature")
                item_data["temperature"] = temp_val

                # Add Final Prompt (serialize if it's a list/dict)
                final_prompt_data = opt_process.get("final_prompt")
                if isinstance(final_prompt_data, (list, dict)):
                     # Try JSON serialization first, fallback to string
                     try:
                         item_data["final_prompt"] = json.dumps(final_prompt_data)
                     except Exception:
                         item_data["final_prompt"] = str(final_prompt_data)
                else:
                    item_data["final_prompt"] = final_prompt_data

                # Add LLM calls to CSV
                item_data["opt_llm_calls"] = opt_process.get("llm_calls_total_optimization")
                flat_data_for_csv.append(item_data)

            # Handle file errors
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
        
        
    def print_summary(self):
        """Print summary of results using Rich table (this is called by print_benchmark_footer)."""
        if not self.results:
            console.print("[yellow]No results to summarize (self.results is empty).[/yellow]")
            return
            
        # This method is effectively replaced by print_benchmark_footer's logic,
        # but if called directly, it should behave like print_benchmark_footer.
        # TODO: Remove this method if not needed
        print_benchmark_footer(self.results)

# TODO: Move this to a separate benchmark_utils.py file
def create_result_panel(
        dataset_name: str,
        optimizer_config_key: str, # Changed parameter name for clarity
        task_detail_data: Optional[Dict[str, Any]] # Allow task_detail_data to be None
    ) -> Panel:
    """Create a consistent panel for displaying optimization results, including the final prompt."""
    
    # Ensure task_detail_data is a dict, even if None was passed
    safe_task_detail_data = task_detail_data or {}

    # Extract details from safe_task_detail_data
    metrics_dict = {}
    time_taken = safe_task_detail_data.get("duration_seconds_task", 0.0)
    optimization_details = safe_task_detail_data.get("optimization_process") or {}
    run_status = safe_task_detail_data.get("status", "failure")
    config_data = safe_task_detail_data.get("config") or {}

    # Populate metrics_dict for scores display
    initial_eval_data = safe_task_detail_data.get("initial_evaluation") or {}
    initial_metrics = initial_eval_data.get("metrics", [])
    final_eval_data = safe_task_detail_data.get("final_evaluation") or {}
    final_eval_metrics = final_eval_data.get("metrics", [])
    
    initial_scores_panel_data = {m.get("metric_name", "unk"): m.get("score") for m in initial_metrics if isinstance(m, dict)} 
    final_scores_panel_data = {f"Final {m.get('metric_name', 'unk')}": m.get("score") for m in final_eval_metrics if isinstance(m, dict)}
    metrics_dict = {**initial_scores_panel_data, **final_scores_panel_data}

    table = Table.grid(padding=(0, 2), expand=True)
    table.add_column(style="dim", width=20)
    table.add_column()

    table.add_row("Dataset:", f"[bold]{dataset_name}[/bold]")
    table.add_row("Optimizer:", f"[bold]{optimizer_config_key}[/bold]")
    table.add_row("Time Taken:", f"{time_taken:.2f}s" if isinstance(time_taken, (float, int)) else "[dim]N/A[/dim]")

    temp_val_panel = config_data.get("optimizer_params", {}).get("temperature")
    if temp_val_panel is None:
        if config_data.get("optimizer_class") == "FewShotBayesianOptimizer":
            raw_opt_res = safe_task_detail_data.get("raw_optimizer_result", {})
            temp_val_panel = raw_opt_res.get("details", {}).get("temperature")
    temp_str_panel = f"{temp_val_panel:.1f}" if isinstance(temp_val_panel, (float, int)) else "[dim]N/A[/dim]"
    table.add_row("Temperature (🔥):", temp_str_panel)

    llm_calls_count = optimization_details.get("llm_calls_total_optimization")
    llm_calls_str = f"[bold cyan]{llm_calls_count}[/bold cyan]" if isinstance(llm_calls_count, int) else "[dim]N/A[/dim]"
    table.add_row("LLM Calls (Opt):", llm_calls_str)

    score_rows = []
    initial_scores_grp = []
    final_scores_grp = []
    percent_changes_grp = []
    initial_score_values = {}

    metric_keys_ordered = []
    for key_str, value in metrics_dict.items():
        if not str(key_str).startswith("Final "):
            clean_name = clean_metric_name(str(key_str))
            if clean_name not in initial_score_values:
                 metric_keys_ordered.append(clean_name)
            initial_score_values[clean_name] = value
    
    for name_key in metric_keys_ordered:
        value = initial_score_values[name_key]
        style = STYLES["success"] if isinstance(value, (float, int)) else STYLES["warning"]
        value_str = f"{value:.4f}" if isinstance(value, (float, int)) else ("[dim]N/A[/dim]" if value is None else str(value))
        initial_scores_grp.append(Text.assemble(f" • {name_key}: ", (value_str, style)))

        final_key_str = f"Final {name_key}"
        final_value = None
        for mk, mv in metrics_dict.items():
            if clean_metric_name(str(mk).replace("Final ", "")) == name_key and str(mk).startswith("Final "):
                final_value = mv
                break
        
        final_style = STYLES["success"] if isinstance(final_value, (float, int)) else STYLES["warning"]
        final_value_str = f"{final_value:.4f}" if isinstance(final_value, (float, int)) else ("[dim]N/A[/dim]" if final_value is None else str(final_value))
        final_scores_grp.append(Text.assemble(f" • {name_key}: ", (final_value_str, final_style)))
        percent_change_text = calculate_percentage_change(value, final_value, name_key)
        percent_changes_grp.append(Text.assemble(f" • {name_key}: ", percent_change_text))

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
        
    history_for_iter_count = optimization_details.get("history", [])
    iter_val = len(history_for_iter_count) if isinstance(history_for_iter_count, list) else 0
    iter_str = f"[bold cyan]{iter_val}[/bold cyan]" if iter_val > 0 else "[dim]N/A[/dim]"
    table.add_row("Iterations:", iter_str)
    best_score_val = optimization_details.get("best_score_achieved")
    best_score_str = f"[bold cyan]{best_score_val:.4f}[/bold cyan]" if isinstance(best_score_val, (float, int)) else "[dim]N/A[/dim]"
    table.add_row("Best Score (Opt):", best_score_str)
    table.add_row()

    # Display FSBO debug info if present
    fsbo_debug_info_str = optimization_details.get("fsbo_final_prompt_debug_info")
    if fsbo_debug_info_str:
        table.add_row("FSBO Debug:", Text(fsbo_debug_info_str, style="dim orange_red1"))
        table.add_row()

    # Display generic task debug notes if present
    task_debug_notes_str = optimization_details.get("task_debug_notes")
    if task_debug_notes_str:
        table.add_row("Debug Notes:", Text(task_debug_notes_str, style="dim orange_red1"))
        table.add_row()

    if "error" in optimization_details:
        table.add_row("Opt. Details:", Text(f"[red]Error: {optimization_details['error'][:100]}...[/red]", overflow="ellipsis"))
    else:
        history = optimization_details.get("history", []) 
        if history and isinstance(history, list):
            history_summary_parts = []
            # Remove or comment out the limit to show all history items
            # limit = 4 
            # indices_to_show = []
            # if len(history) > limit:
            #     indices_to_show.extend(range(limit // 2))
            #     indices_to_show.append(-1) 
            #     indices_to_show.extend(range(len(history) - limit // 2, len(history)))
            # else:
            #     indices_to_show.extend(range(len(history)))

            # Directly iterate over all history items
            for idx, round_data in enumerate(history):
                # if original_idx == -1: # No longer needed with direct iteration
                #     history_summary_parts.append("  ...")
                #     continue
                # round_data = history[original_idx] # No longer needed
                
                if isinstance(round_data, dict):
                    score_val = "N/A"
                    # Try multiple common fields to find score
                    if 'scores' in round_data and isinstance(round_data['scores'], list) and len(round_data['scores']) > 0 and isinstance(round_data['scores'][0], dict):
                        score_val = round_data['scores'][0].get('score')
                    elif 'current_score' in round_data:
                        score_val = round_data['current_score']
                    elif 'best_score' in round_data:
                        score_val = round_data['best_score']
                    
                    score_text_styled: Text
                    if isinstance(score_val, (float, int)):
                        score_text_styled = Text.from_markup(f"[green]{score_val:.4f}[/green]")
                    elif score_val is None:
                        score_text_styled = Text("-", style="dim") # Explicitly show '-' for None scores
                    else:
                        score_text_styled = Text(str(score_val), style="dim") # For other non-numeric (e.g., string "N/A")
                    
                    iteration_num_display = round_data.get('iteration', idx + 1) # Use enumerate index as fallback
                    round_num_display = round_data.get('round_number') 
                    cand_num_display = round_data.get('candidate_in_round')
                    
                    prefix_base = f"  • Iter {iteration_num_display}"
                    suffix_markup = ""
                    if round_num_display is not None and cand_num_display is not None:
                        suffix_markup = f" [dim](R{round_num_display}.C{cand_num_display})[/dim]"
                    elif round_num_display is not None:
                        suffix_markup = f" [dim](R{round_num_display})[/dim]"
                    
                    prefix_styled = Text.from_markup(prefix_base + suffix_markup)
                    history_summary_parts.append(Text.assemble(prefix_styled, Text(": "), score_text_styled))
            
            if history_summary_parts:
                table.add_row("Score History:", Group(*history_summary_parts))

    # Final Prompt Section 
    # final_prompt_data is what create_result_panel uses for its display.
    # It should ONLY come from task_result["final_prompt"]
    final_prompt_data = safe_task_detail_data.get("final_prompt") # No fallback needed if task_result["final_prompt"] is always set

    prompt_content_display: Any
    if final_prompt_data is not None:
        # Handle chat format
        if isinstance(final_prompt_data, list):
            prompt_elements = []
            for msg in final_prompt_data:
                if not isinstance(msg, dict): continue 
                role = msg.get('role', 'unk').capitalize()
                content = msg.get('content', '')
                style = Style()
                if msg.get('role') == 'system': style = Style(color='blue', bold=True)
                elif msg.get('role') == 'user': style = Style(color='green', bold=True)
                elif msg.get('role') == 'assistant': style = Style(color='magenta', bold=True)
                else: style = Style(dim=True)
                prompt_elements.append(Text(f"{role}: ", style=style))
                prompt_elements.append(Text(content, overflow="fold"))
                prompt_elements.append(Text("")) 
            if prompt_elements: prompt_elements.pop()
            prompt_content_display = Group(*prompt_elements) if prompt_elements else Text("[dim](Empty chat list)[/dim]")
        elif isinstance(final_prompt_data, str):
            prompt_content_display = Text(final_prompt_data, overflow="fold")
        else:
            prompt_content_display = Text("[dim](Final prompt is not a recognized string or chat list)[/dim]")
    else:
        prompt_content_display = Text("[dim]Final prompt not available.[/dim]")
    
    final_prompt_panel = Panel(prompt_content_display, title="Final Prompt", border_style="dim", padding=1, expand=True)

    # Combine main table and final prompt panel into a single Group for the main panel
    main_content_group = Group(table, Text("\n"), final_prompt_panel)
    border_style_obj = STYLES.get(run_status.lower(), STYLES.get("default", Style(color="yellow"))) 
    status_text_upper = run_status.upper() 
    escaped_status_text = Text(status_text_upper).plain
    
    status_text_styled: Text
    try:
        status_text_styled = Text(escaped_status_text, style=border_style_obj)
    except Exception as e_style_apply: 
        logger.error(f"[red]Error applying style object '{border_style_obj}' to status '{escaped_status_text}': {e_style_apply}. Using plain text fallback.[/red]")
        color_fallback_str = border_style_obj.color if border_style_obj.color and isinstance(border_style_obj.color, str) else "default"
        if color_fallback_str == "default":
             status_text_styled = Text(f"{escaped_status_text} ({run_status.lower()})") 
        else:
             status_text_styled = Text(f"{escaped_status_text} ({run_status.lower()})", style=color_fallback_str)

    panel_title_text = Text.assemble(status_text_styled, f" {optimizer_config_key} on {dataset_name}") # Use optimizer_config_key
    return Panel(main_content_group, title=panel_title_text, border_style=border_style_obj, padding=(1, 2), expand=True)

# TODO: Move this to a separate benchmark_utils.py file
def print_benchmark_header(
        datasets: List[str],
        optimizers: List[str],
        test_mode: bool
    ):
    """Print a clean header for the benchmark run."""
    console.print(Rule("[bold blue]Benchmark Configuration[/bold blue]", style="blue"))
    
    table = Table(box=box.ROUNDED, show_header=False, padding=(0, 1))
    table.add_row("Datasets", ", ".join(datasets), style=STYLES["header"])
    table.add_row("Optimizers", ", ".join(optimizers), style=STYLES["header"])
    table.add_row("Test Mode", str(test_mode), style=STYLES["info"])
    
    console.print(Panel(table, border_style="blue", padding=(1, 2)))
    console.print()

def print_benchmark_footer(results: List[dict], successful_tasks: int, failed_tasks: int, total_duration: float, completed_display_items: List[Panel]):
    """Print footer with stats, pivoted results table, and individual panels+prompts."""
    console.print(Rule("[bold blue]Benchmark Run Complete[/bold blue]", style="blue"))
    
    # Overall Statistics Panel
    summary_table = Table(box=box.ROUNDED, show_header=False, padding=(0,1), show_edge=False)
    summary_table.add_row("Total Benchmarks Run:", f"[bold cyan]{successful_tasks + failed_tasks}[/bold cyan]")
    summary_table.add_row("Successful Tasks:", f"[bold green]{successful_tasks}[/bold green]")
    summary_table.add_row("Failed Tasks:", f"[bold red]{failed_tasks}[/bold red]")
    summary_table.add_row("Total Duration:", f"[cyan]{total_duration:.2f}s[/cyan]")
    console.print(Panel(summary_table, title="Overall Statistics", border_style="blue", padding=(1,2), expand=False))

    # Detailed Pivoted Results Table
    if results: 
        logger.info("Generating detailed pivoted results table for footer...")
        results_table = Table(box=box.SIMPLE_HEAVY, show_header=True, header_style=STYLES["header"], title="Detailed Results Summary", title_style="dim", show_lines=True, padding=(0,1,0,1))
        results_table.add_column("Dataset", style=STYLES["dim"], max_width=25, overflow="ellipsis", no_wrap=True)
        results_table.add_column("Optimizer", max_width=25, overflow="fold", no_wrap=False)
        results_table.add_column("Model", no_wrap=True, max_width=20, overflow="ellipsis")
        results_table.add_column("🔥", justify="center", no_wrap=True)
        results_table.add_column("☎️", justify="right", no_wrap=True)
        results_table.add_column("Metric", no_wrap=True)
        results_table.add_column("Run (s)", justify="right", no_wrap=True)
        results_table.add_column("Initial", justify="right", no_wrap=True)
        results_table.add_column("Final", justify="right", no_wrap=True)
        results_table.add_column("% Change", justify="right", no_wrap=True)
        
        all_metrics_names = set()
        # processed_rows will store data indexed by (dataset_name, optimizer_name, model_name)
        # Each value will be like: {"initial": {metric_name: score}, "final": {metric_name: score}, "time": time_taken, "task_id": ...}
        processed_data_for_table = {}

        for task_summary in results: # task_summary is an item from self.results
            if task_summary.get("status") != "success" or not task_summary.get("json_file_path"):
                # Skip non-successful or tasks without detailed JSON for this table
                continue
            try:
                with open(task_summary["json_file_path"], "r") as f_detail:
                    detail_data = json.load(f_detail)
                
                config = detail_data.get("config", {})
                dataset_name = config.get("dataset_config_name", "N/A")
                optimizer_name = config.get("optimizer_config_name", "N/A")
                model_name = config.get("model_name", "N/A") 
                time_taken = detail_data.get("duration_seconds_task", 0)
                task_id = detail_data.get("task_id", "N/A")

                # Ensure 'config' dictionary is fetched from detail_data, this is the correct scope
                current_task_config_data = detail_data.get("config", {})
                table_key = (dataset_name, optimizer_name, model_name)
                if table_key not in processed_data_for_table:
                    processed_data_for_table[table_key] = {
                        "initial": {},
                        "final": {},
                        "time": time_taken,
                        "task_id": task_id, 
                        "temperature": None,
                        "llm_calls_total_optimization": None
                    }
                else: 
                    processed_data_for_table[table_key]["time"] = time_taken 
                
                temp_val_table = None
                # Use current_task_config_data here
                if current_task_config_data.get("optimizer_class") == "FewShotBayesianOptimizer":
                    raw_opt_res_table = detail_data.get("raw_optimizer_result", {})
                    temp_val_table = raw_opt_res_table.get("details", {}).get("temperature")
                else:
                    exp_params_table = current_task_config_data.get("parameters", {})
                    if not exp_params_table: 
                         exp_params_table = current_task_config_data.get("optimizer_params", {})
                    temp_val_table = exp_params_table.get("temperature")
                processed_data_for_table[table_key]["temperature"] = temp_val_table
                
                # Fetch and store LLM calls for the summary table
                opt_process_summary = detail_data.get("optimization_process", {})
                processed_data_for_table[table_key]["llm_calls_total_optimization"] = opt_process_summary.get("llm_calls_total_optimization")

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

            # TODO: Move this to a separate benchmark error handling section
            except FileNotFoundError:
                logger.warning(f"[yellow]Footer Table: JSON file not found for task {task_summary.get('task_id')}: {task_summary.get('json_file_path')}[/yellow]")
            except json.JSONDecodeError:
                logger.warning(f"[yellow]Footer Table: Error decoding JSON for task {task_summary.get('task_id')}: {task_summary.get('json_file_path')}[/yellow]")
            except Exception as e:
                logger.warning(f"[yellow]Footer Table: Error processing task JSON {task_summary.get('json_file_path')} for table: {e}[/yellow]")

        # Sort by dataset, then optimizer, then model for consistent table output
        sorted_table_keys = sorted(processed_data_for_table.keys())
        last_dataset_optimizer_model = None

        for i, key_tuple in enumerate(sorted_table_keys):
            dataset, optimizer, model = key_tuple
            data_for_run_key = processed_data_for_table[key_tuple]
            time_taken_for_run = data_for_run_key.get("time", 0)
            temperature_for_run = data_for_run_key.get("temperature")
            llm_calls_for_run = data_for_run_key.get("llm_calls_total_optimization")
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
                    model_parts = model.split('/')
                    model_text = Text('\n'.join(model_parts), overflow="ellipsis")
                    model_text.truncate(20)
                    
                    display_dataset = dataset_text if metric_i == 0 else ""
                    display_optimizer = optimizer if metric_i == 0 else ""
                    display_model = model_text if metric_i == 0 else ""
                    display_temp = f"{temperature_for_run:.1f}" if isinstance(temperature_for_run, (int, float)) else "[dim]-[/dim]" if metric_i == 0 else ""
                    display_llm_calls = str(llm_calls_for_run) if llm_calls_for_run is not None and metric_i == 0 else ("[dim]-[/dim]" if metric_i == 0 else "") # Display LLM calls
                    display_time = f"{time_taken_for_run:.2f}" if metric_i == 0 else ""
                    
                    # Add a line (end_section)
                    end_section_flag = is_new_block and i > 0 and metric_i == 0

                    results_table.add_row(
                        display_dataset,
                        display_optimizer,
                        display_model, 
                        display_temp,
                        display_llm_calls,
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

    # Individual Task Panels + Final Prompts
    console.print(Rule("Individual Task Results & Final Prompts", style="dim blue"))
    if completed_display_items:
        for panel in completed_display_items:
            console.print(panel)
            console.print()
    else:
        console.print("[yellow]No individual task panels were generated.[/yellow]")

# TODO: Move this to a separate benchmark_utils.py file
def calculate_percentage_change(
        initial: Optional[float],
        final: Optional[float],
        metric_name: str
    ) -> Text:
    """Calculate the percentage change between two values."""

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

    # Temporarily set to DEBUG for console
    # TODO: Set in config
    setup_logging(level=logging.INFO, force=True)
    t_log_setup = time.perf_counter()
    logger.info(f"Initial logging setup took {t_log_setup - t_start:.4f}s")
    
    # Aggressive Logger Suppression
    # Silence opik core directly and forcefully
    opik_logger = logging.getLogger("opik")
    opik_logger.setLevel(logging.CRITICAL)
    opik_logger.propagate = False
    for handler in opik_logger.handlers[:]:
        opik_logger.removeHandler(handler)
    opik_logger.addHandler(logging.NullHandler())
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
        lib_logger.setLevel(logging.WARNING)
    logger.info(f"[yellow]Set level to WARNING for: {', '.join(noisy_libs)}[/yellow]")

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

    # Only prompt if interactive
    if not operational_args_provided and sys.stdin.isatty():
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
