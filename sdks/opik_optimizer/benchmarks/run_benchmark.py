import argparse
import copy
import json
import logging
import os
import random
import sys
import time
import traceback
from concurrent.futures import ProcessPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
import opik
import pandas as pd
from benchmark_checkpoints import BenchmarkCheckpoint, BenchmarkCheckpointManager
from benchmark_config import (
    INITIAL_PROMPTS,
    MODELS_TO_RUN,
    OPTIMIZER_CONFIGS,
    get_dataset_config,
    get_experiment_config,
    get_project_config,
)
from benchmark_monitor import get_optimization_monitor
from benchmark_utils import clean_metric_name, custom_json_serializer
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision
from opik.evaluation.metrics.llm_judges.context_recall.metric import ContextRecall
from pydantic import BaseModel
from rich import box
from rich.align import Align
from rich.console import Console, Group
from rich.live import Live
from rich.padding import Padding
from rich.panel import Panel

# Rich imports
from rich.progress import (
    BarColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
    TimeRemainingColumn,
)
from rich.rule import Rule
from rich.style import Style
from rich.table import Table
from rich.text import Text

import opik_optimizer
from opik_optimizer import (
    MetricConfig,
    OptimizationResult,
    base_optimizer,
    from_dataset_field,
    from_llm_response_text,
    reporting_utils,
)
from opik_optimizer.cache_config import initialize_cache
from opik_optimizer.optimization_config import chat_prompt

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



class BenchmarkDatasetConfig(BaseModel):
    model_config = {"arbitrary_types_allowed":True}

    name: str
    metrics: List[BaseMetric]
    input_key: str
    output_key: str
    dataset: opik.Dataset

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

class OptimizationTask(BaseModel):
    model_config = {"arbitrary_types_allowed":True}

    dataset_name: str
    optimizer_name: str
    model_to_run: str

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
        self.project_name = self.project_config.name
        self.workspace = self.project_config.workspace
        
        # Set global random seed
        # TODO: Move to opik_optimizer.utils and use across all benchmarks as opik_utils.set_global_random_seed()
        random.seed(seed)
        np.random.seed(seed)
        logger.info(f"Global random seed set to [bold cyan]{seed}[/bold cyan]")
        
        # Initialize shared cache
        initialize_cache()
        logger.info("[green]Shared cache initialized.[/green]")
        
        # Checkpoint handling
        self.checkpoint_dir = self.output_dir / "checkpoints"
        self.checkpoint_dir.mkdir(exist_ok=True)
        self.checkpoint_manager = BenchmarkCheckpointManager(self.checkpoint_dir)
        self.load_latest_checkpoint()

    def load_latest_checkpoint(self):
        """Load the latest checkpoint if it exists, or a specific one if retry_failed_run_id is set, using the new BenchmarkCheckpointManager abstraction."""
        checkpoint = self.checkpoint_manager.load_latest_checkpoint(
            resume_enabled=self.resume_enabled,
            retry_failed_run_id=self.retry_failed_run_id
        )
        if not checkpoint:
            logger.info("No checkpoint to load (either not resuming, not retrying, or none found). Starting a fresh run state.")
            self.results = []
            self.current_run_id = None
            self.task_results_dir = None
            self.resuming_run_active = False
            return

        # Restore state from checkpoint
        self.current_run_id = checkpoint.run_id
        if checkpoint.task_results_dir:
            self.task_results_dir = Path(checkpoint.task_results_dir)
            logger.info(f"Restored task_results_dir: {self.task_results_dir}")
        else:
            if self.current_run_id:
                self.task_results_dir = self.output_dir / "task_results" / self.current_run_id
                logger.warning(f"[yellow]task_results_dir not found in checkpoint, inferred to: {self.task_results_dir}[/yellow]")
                self.task_results_dir.mkdir(parents=True, exist_ok=True)
            else:
                self.task_results_dir = None
                logger.warning("[yellow]run_id and task_results_dir not found in checkpoint. Resuming might be problematic.[/yellow]")

        self.results = checkpoint.results_summary
        self.test_mode = checkpoint.test_mode
        self.project_config = checkpoint.project_config
        if self.project_config:
            self.project_name = self.project_config.get("name", self.project_name)
            self.workspace = self.project_config.get("workspace", self.workspace)

        # Restore monitor state
        monitor_state = checkpoint.monitor_state or {}
        if hasattr(self.monitor, 'metrics_history'):
            self.monitor.metrics_history = monitor_state.get("metrics_history", [])
        if hasattr(self.monitor, 'prompts_history'):
            self.monitor.prompts_history = monitor_state.get("prompts_history", [])

        env_details = checkpoint.environment or {}
        logger.info(f"Checkpoint loaded. Run ID: [bold yellow]{self.current_run_id}[/bold yellow]")
        logger.info(f"  Restored [cyan]{len(self.results)}[/cyan] task summaries from checkpoint.")
        logger.info(f"  Restored [cyan]{len(self.monitor.metrics_history)}[/cyan] metrics history entries.")
        logger.info(f"  Restored [cyan]{len(self.monitor.prompts_history)}[/cyan] prompts history entries.")
        logger.info(f"  Original run seed: {env_details.get('seed')}, max_workers: {env_details.get('max_workers')}")

        if self.resume_enabled or self.retry_failed_run_id:
            self.resuming_run_active = True
            if self.retry_failed_run_id:
                run_id_from_checkpoint = checkpoint.run_id
                logger.info(f"[green]Checkpoint for run_id '{run_id_from_checkpoint}' loaded successfully for retrying failed tasks.[/green]")
            elif self.resume_enabled:
                current_run_id_for_log = self.current_run_id
                logger.info(f"[green]Checkpoint loaded successfully and resume mode is active for run_id '{current_run_id_for_log}'.[/green]")
        else:
            self.resuming_run_active = False
            logger.info("Resume mode not enabled. Checkpoint data loaded, but all tasks will be scheduled if not already present in a *new* run.")

    def save_checkpoint(self):
        """Save current state to a checkpoint file using the new BenchmarkCheckpointManager abstraction."""
        if not hasattr(self, 'current_run_id') or not self.current_run_id:
            logger.warning("[yellow]Cannot save checkpoint: current_run_id is not set. Benchmark might not have started properly.[/yellow]")
            return

        checkpoint: BenchmarkCheckpoint = BenchmarkCheckpoint.from_runner_state(
            run_id=self.current_run_id,
            task_results_dir=self.task_results_dir,
            test_mode=self.test_mode,
            project_config=self.project_config,
            results_summary=self.results,
            dataset_cache_keys=list(self.dataset_cache.keys()),
            monitor_state={
                "metrics_history": getattr(self.monitor, 'metrics_history', []),
                "prompts_history": getattr(self.monitor, 'prompts_history', [])
            },
            environment={
                "python_version": sys.version,
                "opik_optimizer_version": opik_optimizer.__version__,
                "seed": self.seed,
                "max_workers": self.max_workers
            }
        )
        self.checkpoint_manager.save_checkpoint(checkpoint)

    def create_optimizer(
            self,
            optimizer_config: BenchmarkOptimizerConfig,
            model_name: str,
            project_name: str
        ) -> Any:
        """Create optimizer instance based on configuration."""
        optimizer_class_name = optimizer_config.class_name
        params = optimizer_config.params.copy()
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
            optimizer_class = getattr(opik_optimizer, optimizer_class_name)
        except Exception:
            raise ValueError(f"Failed to resolve optimizer class: {optimizer_class_name}")
        
        try:
            result = optimizer_class(**params)
        except Exception as e:
            tb = traceback.format_exc()
            raise ValueError(f"Failed to initialize optimizer class '{optimizer_class_name}' - Traceback:{tb}")
        
        return result

    def _init_task_result(
        self,
        task_id: str,
        initial_prompt: chat_prompt.ChatPrompt,
        experiment_config: BenchmarkExperimentConfig,
        optimizer: Any,
    ):
        dataset_name = experiment_config.dataset_name
        optimizer_config_name_display = experiment_config.optimizer
        actual_optimizer_class_name = type(optimizer).__name__
        actual_optimizer_class_name_display = actual_optimizer_class_name.lower().replace("_", "-")
        model_name_display = optimizer.model

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
                "optimizer_config_name": experiment_config.optimizer,
                "optimizer_class": actual_optimizer_class_name_display,
                "model_name": model_name_display,
                "optimizer_params": OPTIMIZER_CONFIGS[experiment_config.optimizer].params,
                "initial_prompt": initial_prompt.formatted_messages,
            },
            "initial_evaluation": None,
            "optimization_process": None,
            "final_evaluation": None,
            "raw_optimizer_result": None,
        }
        
        # Store original class name for precise class checks later
        task_result["optimizer_original_class_name"] = actual_optimizer_class_name
        
        console.print(f"🏁 Starting task: [bold magenta]{task_id}[/bold magenta] ({dataset_name} / {optimizer_config_name_display} / {model_name_display})")
        return task_result

    def _create_metric_config(self, metric: BaseMetric, input_key: str, output_key: str) -> MetricConfig:
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
        
        return metric_config_eval

    def run_optimization(
            self,
            initial_prompt: chat_prompt.ChatPrompt,
            optimizer: base_optimizer.BaseOptimizer,
            dataset: opik.Dataset,
            metrics: List[Any],
            input_key: str,
            output_key: str,
            experiment_config: BenchmarkExperimentConfig
        ) -> Optional[Dict]:
        """Run optimization for a single task."""
        return {}

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
        timestamp_for_task_id = datetime.now().strftime('%Y%m%d%H%M%S%f')
        task_id = f"{experiment_config.dataset_name}-{experiment_config.optimizer}-{model_name_for_task_id}-{timestamp_for_task_id}"

        # Initialize the task result object
        task_result = self._init_task_result(
            task_id=task_id,
            initial_prompt=initial_prompt,
            experiment_config=experiment_config,
            optimizer=optimizer
        )
        # List to collect errors during evaluation steps
        evaluation_errors = []
        try:
            # Initial Prompt Evaluation --- 
            logger.debug("--> Evaluating initial prompt...")
            start_time_eval_initial = time.time()
            initial_scores = {}
            for metric in metrics:
                metric_config = self._create_metric_config(metric, input_key, output_key)

                with reporting_utils.convert_tqdm_to_rich(verbose=0):
                    res = optimizer.evaluate_prompt(
                        prompt=initial_prompt,
                        dataset=dataset,
                        metric_config=metric_config
                    )

                initial_scores[metric.name]= res
            
            # Store initial evaluation results properly
            task_result["initial_evaluation"] = {
                "metrics": [
                    {
                        "metric_name": metric,
                        "score": score,
                        "timestamp": datetime.now().isoformat()
                    }
                    for metric, score in initial_scores.items()
                ],
                "duration_seconds": time.time() - start_time_eval_initial,
                "prompt_used": initial_prompt.formatted_messages
            }

            # Run Optimization
            n_trials_log = getattr(optimizer, 'n_trials', 'N/A')
            logger.info(f"--> Running optimization ({n_trials_log} trials)...")
            start_time_opt = time.time()
            results_obj = None
            actual_optimizer_class_name_display = optimizer.__class__.__name__
            
            try:
                metric_config = self._create_metric_config(metrics[0], input_key, output_key)
                results_obj: OptimizationResult = optimizer.optimize_prompt(
                    prompt=initial_prompt,
                    dataset=dataset,
                    metric_config=metric_config,
                    experiment_config=experiment_config.model_dump()
                )

                task_result["raw_optimizer_result"] = results_obj.model_dump()

            except Exception as e:
                logger.error(f"[red]Error during {actual_optimizer_class_name_display}.optimize_prompt: {e}[/red]")
                logger.exception("Traceback:")
                task_result["error_message"] = f"Error in {actual_optimizer_class_name_display}.optimize_prompt: {str(e)}"
                task_result["timestamp_end_task"] = datetime.now().isoformat()
                task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                return task_result

            opt_time = time.time() - start_time_opt
            if results_obj is None:
                logger.error(f"[bold red]Optimization failed for {actual_optimizer_class_name_display}. results_obj is None.[/bold red]")
                task_result["error_message"] = f"Optimization failed for {actual_optimizer_class_name_display}, results_obj is None."
                task_result["timestamp_end_task"] = datetime.now().isoformat()
                task_result["duration_seconds_task"] = time.time() - start_time_run_opt
                return task_result

            # Process optimization history for structured logging
            task_result["optimization_process"] = {
                "timestamp_start": datetime.fromtimestamp(start_time_opt).isoformat(),
                "timestamp_end": datetime.now().isoformat(),
                "duration_seconds": opt_time,
                "optimizer_type": actual_optimizer_class_name_display,
                "num_trials_configured": getattr(optimizer, 'n_trials', getattr(optimizer, 'n_iterations', None)),
                "num_samples_configured": getattr(optimizer, 'n_samples', None),
                "best_score_achieved": getattr(results_obj, 'score', None), 
                "final_prompt": results_obj.details.get("chat_messages") if actual_optimizer_class_name_display == "fewshotbayesianoptimizer" and hasattr(results_obj, 'details') and isinstance(results_obj.details, dict) and "chat_messages" in results_obj.details else getattr(results_obj, 'prompt', None), 
                "history": results_obj.history,
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
            evaluation_errors = []
            start_time_eval_final = time.time()
            
            # Determine final_prompt_to_eval (representation of what the optimizer produced for logging)
            # Also determine actual_prompt_for_submission (what is actually sent to optimizer.evaluate_prompt)

            # Initialize task_debug_notes in optimization_process dict
            if "optimization_process" not in task_result or task_result["optimization_process"] is None:
                task_result["optimization_process"] = {}
            task_result["optimization_process"]["task_debug_notes"] = None

            # Compute the final performance, required as not all metrics are used in the optimize method
            final_prompt = results_obj.prompt
            task_result["final_prompt"] = final_prompt

            # Determine final scores
            for metric in metrics:
                try:
                    metric_config_final_eval = self._create_metric_config(metric, input_key, output_key)
                    eval_n_samples_final = 5 if self.test_mode else None
                    
                    with reporting_utils.convert_tqdm_to_rich(verbose=0):
                        res = optimizer.evaluate_prompt(
                            prompt=chat_prompt.ChatPrompt(messages=final_prompt),
                            dataset=dataset,
                            metric_config=metric_config_final_eval,
                            experiment_config=experiment_config.model_dump(),
                            n_samples=eval_n_samples_final
                        )
                    final_scores[metric.name] = res
                except Exception as e:
                    final_scores[metric.name] = None
                    evaluation_errors.append(f"Error during evaluation of metric {metric.name}: {str(e)} - {traceback.format_exc()}")

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
                "duration_seconds": time.time() - start_time_eval_final, 
                "prompt_used": final_prompt
            }

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

            task_result={}
            return task_result
        
        except Exception as e:
            logger.error(f"[red]Unexpected error in run_opt for {dataset.name}/{actual_optimizer_class_name_display} (task: {task_id}): {e}[/red]")
            logger.exception("Traceback:")
            task_result["error_message"] = f"Outer exception in run_optimization: {str(e)} - Traceback: {traceback.format_exc()}"
            task_result["status"] = "failure"
            task_result["timestamp_end_task"] = datetime.now().isoformat()
            task_result["duration_seconds_task"] = time.time() - start_time_run_opt

            task_result={}
            return task_result

    def run_benchmark(
            self,
            datasets: List[str],
            optimizers: List[str]
        ):
        """Run benchmark with Live display showing overall progress and active tasks."""
        overall_start_time = time.time()
        
        print_benchmark_header(datasets, optimizers, self.test_mode)

        total_tasks = len(datasets) * len(optimizers) * len(MODELS_TO_RUN) # Added MODELS_TO_RUN
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
        for dataset_name in datasets:
            for optimizer_name in optimizers:
                for model_name_prog in MODELS_TO_RUN: # Added model loop
                    tasks_to_plan_for_progress.append((dataset_name, optimizer_name, model_name_prog))
        
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
            with ProcessPoolExecutor(max_workers=self.max_workers) as executor:
                # Submission Loop 
                tasks_to_plan: List[OptimizationTask] = []
                for dataset_name in datasets:
                    for optimizer_name in optimizers:
                        for model_to_run in MODELS_TO_RUN:
                            # Sanitize model name for use in IDs/paths
                            tasks_to_plan.append(
                                OptimizationTask(
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    model_to_run=model_to_run
                                )
                            )
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

                for optimization_task in tasks_to_plan:
                    dataset_key: opik.Dataset = optimization_task.dataset_name
                    optimizer_key: str = optimization_task.optimizer_name
                    model_name: str = optimization_task.model_to_run
                    sanitized_model_name_for_ids = model_name.replace("/", "-")
                    
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
                    
                    project_name_opik = f"benchmark-{self.current_run_id}-{dataset_key}-{optimizer_key}-{sanitized_model_name_for_ids}-{datetime.now().strftime('%Y%m%d%H%M%S')}"
                    optimizer_instance = self.create_optimizer(OPTIMIZER_CONFIGS[optimizer_key], model_name, project_name_opik)
                    
                    if optimizer_instance is None:
                        logger.error(f"[red]✗ Failed create optimizer {optimizer_key}/{dataset_key} for model {model_name}. Skip.[/red]")
                        failed_tasks += 1
                        progress.update(overall_progress_task, advance=1)
                        summary_line.plain = f"Run: {self.current_run_id} | Tasks: {successful_tasks+failed_tasks}/{total_tasks} | Success: {successful_tasks} | Failed: {failed_tasks} | Active: {len(active_tasks_status)}"
                        live.update(generate_live_display())
                        continue
                        
                    exp_config = get_experiment_config(dataset_key, optimizer_key, model_name, test_mode=self.test_mode)
                    dataset_config: BenchmarkDatasetConfig = get_dataset_config(self.test_mode)[dataset_key]
                    
                    current_future = executor.submit( 
                        self.run_optimization,
                        dataset=dataset_config.dataset, 
                        optimizer=optimizer_instance, 
                        metrics=dataset_config.metrics, 
                        initial_prompt=chat_prompt.ChatPrompt(messages=INITIAL_PROMPTS[dataset_key]), 
                        input_key=dataset_config.input_key, 
                        output_key=dataset_config.output_key, 
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
                                final_eval_data = result_data.get("final_evaluation")
                                
                                if final_eval_data is not None:
                                    final_eval_metrics = final_eval_data.get("metrics", [])
                                    if final_eval_metrics and isinstance(final_eval_metrics, list) and len(final_eval_metrics) > 0:
                                        task_summary_for_results_list["final_primary_score"] = final_eval_metrics[0].get("score")
                            else:
                                logger.warning(f"[yellow]Task {task_id_for_log} reported status: {result_data.get('status')}. Error: {result_data.get('error_message')}. Marked failed.[/yellow]")
                                failed_tasks += 1
                            
                            self.results.append(task_summary_for_results_list)

                        else: # result_data is None or not a dict (should not happen with new run_optimization)
                            logger.warning(f"[yellow]Task {d_key}/{o_key} (meta desc: {task_desc_short}) returned invalid data (None or not dict). Marked failed.[/yellow]")
                            failed_tasks += 1
                            
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
                        display_dataset_name = get_dataset_config(self.test_mode)[dataset_name].name
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
                    metric_display_name = metric_entry.get("metric_name", "Unknown")
                    all_metrics_names.add(metric_display_name)
                    processed_data_for_table[table_key]["initial"][metric_display_name] = metric_entry.get("score")

                final_eval_metrics = detail_data.get("final_evaluation", {}).get("metrics", [])
                for metric_entry in final_eval_metrics:
                    metric_display_name = metric_entry.get("metric_name", "Unknown")
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
    parser.add_argument("--datasets", type=str, nargs='*', default=None, help=f"Space-separated list of dataset keys to run. Available: {list(get_dataset_config().keys())}")
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
        datasets=args.datasets if args.datasets else get_dataset_config(args.test_mode).keys(),
        optimizers=args.optimizers if args.optimizers else list(OPTIMIZER_CONFIGS.keys())
    )
    t_run_end = time.perf_counter()
    logger.info(f"Benchmark run method finished in {t_run_end - t_run_start:.4f}s")

if __name__ == "__main__":
    with reporting_utils.suppress_opik_logs():
        main() 
