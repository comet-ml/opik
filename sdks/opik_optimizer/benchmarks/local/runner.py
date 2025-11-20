"""Local benchmark runner that schedules optimization tasks on the host machine.

The CLI entry point (`run_benchmark_local.py`) instantiates `BenchmarkRunner`
from this module.  It is responsible for:
  * building the product of datasets/optimizers/models (or using a
    manifest) and persisting checkpoints so runs can be resumed,
  * launching `run_optimization` either in a process pool or sequentially when
    multiprocessing is not available,
  * enforcing rollout budgets by deriving `max_trials` when manifests do not
    override optimizer parameters.
"""

import os
import sys
import time
import traceback
from concurrent.futures import Future, ProcessPoolExecutor, wait, FIRST_COMPLETED
from datetime import datetime
from typing import Any

from local import checkpoint as benchmark_checkpoint
from local import logging as benchmark_logging
import benchmark_config
from benchmark_task import (
    TaskEvaluationResult,
    TaskResult,
    TASK_STATUS_FAILED,
    TASK_STATUS_PENDING,
    TASK_STATUS_RUNNING,
    TASK_STATUS_SUCCESS,
)
from benchmark_taskspec import BenchmarkTaskSpec

import opik_optimizer
import opik_optimizer.datasets
from opik_optimizer import (
    BaseOptimizer,
    reporting_utils,
    ChatPrompt,
)

from utils.budgeting import resolve_optimize_params


@benchmark_logging.log_console_output_to_file()
def run_optimization(
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    test_mode: bool,
    optimizer_params_override: dict[str, Any] | None = None,
    optimizer_prompt_params_override: dict[str, Any] | None = None,
) -> TaskResult:
    timestamp_start = time.time()

    initial_prompt = None
    optimized_prompt = None

    with reporting_utils.suppress_opik_logs():
        try:
            # Initialize the dataset, optimizer_class, metrics and initial_prompt
            dataset_config = benchmark_config.DATASET_CONFIG[dataset_name]
            dataset = getattr(opik_optimizer.datasets, dataset_name)(
                test_mode=test_mode
            )

            optimizer_config = benchmark_config.OPTIMIZER_CONFIGS[optimizer_name]
            constructor_kwargs = dict(optimizer_config.params)
            if optimizer_params_override:
                constructor_kwargs.update(optimizer_params_override)
            optimizer: BaseOptimizer = getattr(
                opik_optimizer, optimizer_config.class_name
            )(model=model_name, **constructor_kwargs)

            messages = benchmark_config.INITIAL_PROMPTS[dataset_name]
            initial_prompt = ChatPrompt(messages=messages)  # type: ignore

            # Start by running a first evaluation
            start_time_initial_eval = time.time()
            initial_evaluation = []
            for metric_ in dataset_config.metrics:
                result = optimizer.evaluate_prompt(
                    prompt=initial_prompt, dataset=dataset, metric=metric_, n_threads=4
                )
                initial_evaluation.append(
                    {
                        "metric_name": metric_.__name__,
                        "score": result,
                        "timestamp": time.time(),
                    }
                )
            initial_evaluation_duration = time.time() - start_time_initial_eval

            # Run optimization
            optimize_kwargs = dict(optimizer_config.optimize_params)
            if optimizer_prompt_params_override:
                optimize_kwargs.update(optimizer_prompt_params_override)
            optimization_results = optimizer.optimize_prompt(
                prompt=initial_prompt,
                dataset=dataset,
                metric=dataset_config.metrics[0],
                **optimize_kwargs,
            )
            optimized_prompt = ChatPrompt(messages=optimization_results.prompt)

            # Run final evaluation
            start_time_final_eval = time.time()
            optimized_evaluation = []
            for metric_ in dataset_config.metrics:
                result = optimizer.evaluate_prompt(
                    prompt=optimized_prompt,
                    dataset=dataset,
                    metric=metric_,
                    n_threads=4,
                )
                optimized_evaluation.append(
                    {
                        "metric_name": metric_.__name__,
                        "score": result,
                        "timestamp": time.time(),
                    }
                )
            optimized_evaluation_duration = time.time() - start_time_final_eval

            return TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status=TASK_STATUS_SUCCESS,
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                initial_evaluation=TaskEvaluationResult(
                    metrics=initial_evaluation,  # type: ignore
                    duration_seconds=initial_evaluation_duration,
                ),
                optimized_prompt=optimized_prompt,
                optimized_evaluation=TaskEvaluationResult(
                    metrics=optimized_evaluation,  # type: ignore
                    duration_seconds=optimized_evaluation_duration,
                ),
                error_message=None,
                llm_calls_total_optimization=optimization_results.llm_calls,
                optimization_raw_result=optimization_results,
                timestamp_end=time.time(),
            )
        except Exception:
            return TaskResult(
                id=f"{dataset_name}_{optimizer_name}_{model_name}",
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status=TASK_STATUS_FAILED,
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                error_message=traceback.format_exc(),
                timestamp_end=time.time(),
            )


class BenchmarkRunner:
    run_id: str | None = None

    def __init__(
        self, max_workers: int, seed: int, test_mode: bool, checkpoint_dir: str
    ) -> None:
        self.max_workers = max_workers
        self.seed = seed
        self.test_mode = test_mode
        self.benchmark_logger = benchmark_logging.BenchmarkLogger()
        self.checkpoint_dir = checkpoint_dir

    def run_benchmarks(
        self,
        demo_datasets: list[str],
        optimizers: list[str],
        models: list[str],
        retry_failed_run_id: str | None,
        resume_run_id: str | None,
        task_specs: list[BenchmarkTaskSpec] | None = None,
    ) -> None:
        # Create unique id
        if resume_run_id and retry_failed_run_id:
            raise ValueError("Cannot resume and retry at the same time")
        elif resume_run_id:
            self.run_id = resume_run_id
        elif retry_failed_run_id:
            self.run_id = retry_failed_run_id
        else:
            self.run_id = (
                f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{os.urandom(4).hex()}"
            )

        if task_specs is None:
            tasks: list[BenchmarkTaskSpec] = [
                BenchmarkTaskSpec(
                    dataset_name=dataset_name,
                    optimizer_name=optimizer_name,
                    model_name=model_name,
                    test_mode=self.test_mode,
                )
                for dataset_name in demo_datasets
                for optimizer_name in optimizers
                for model_name in models
            ]
        else:
            tasks = task_specs

        datasets_for_log = sorted({task.dataset_name for task in tasks})
        optimizers_for_log = sorted({task.optimizer_name for task in tasks})
        models_for_log = sorted({task.model_name for task in tasks})

        # Initialize logger
        checkpoint_folder = os.path.join(self.checkpoint_dir, self.run_id)
        self.benchmark_logger.setup_logger(
            datasets_for_log,
            optimizers_for_log,
            models_for_log,
            self.test_mode,
            self.run_id,
        )
        self.benchmark_logger.print_benchmark_header()

        # Initialize BenchmarkCheckpointManager
        checkpoint_manager = benchmark_checkpoint.BenchmarkCheckpointManager(
            checkpoint_folder=checkpoint_folder,
            run_id=self.run_id,
            test_mode=self.test_mode,
            demo_datasets=datasets_for_log,
            optimizers=optimizers_for_log,
            models=models_for_log,
            task_specs=tasks,
        )
        if resume_run_id or retry_failed_run_id:
            checkpoint_manager.load()
            tasks = checkpoint_manager.task_specs
            datasets_for_log = checkpoint_manager.demo_datasets
            optimizers_for_log = checkpoint_manager.optimizers
            models_for_log = checkpoint_manager.models
        else:
            checkpoint_manager.save()

        # Start scheduling the tasks
        start_time = time.time()

        task_results: list[TaskResult] = []
        with self.benchmark_logger.create_live_panel() as live:
            live.update(self.benchmark_logger._generate_live_display_message())

            with ProcessPoolExecutor(max_workers=self.max_workers) as executor:
                # Submit all tasks and map futures to metadata
                future_to_info: dict[Future[TaskResult], tuple[str, str, str, str]] = {}

                failed_ids = {
                    x.id
                    for x in checkpoint_manager.task_results
                    if x.status == TASK_STATUS_FAILED
                }
                completed_ids = {
                    x.id
                    for x in checkpoint_manager.task_results
                    if x.status not in (TASK_STATUS_PENDING, TASK_STATUS_FAILED)
                }

                for task in tasks:
                    task_id = task.task_id

                    if retry_failed_run_id and task_id not in failed_ids:
                        continue

                    if resume_run_id and task_id in completed_ids:
                        continue

                    optimize_override = resolve_optimize_params(
                        task.dataset_name, task.optimizer_name, task.optimize_params
                    )
                    future = executor.submit(
                        run_optimization,
                        task_id=task_id,
                        dataset_name=task.dataset_name,
                        optimizer_name=task.optimizer_name,
                        model_name=task.model_name,
                        test_mode=task.test_mode,
                        optimizer_params_override=task.optimizer_params,
                        optimizer_prompt_params_override=optimize_override,
                    )

                    future_to_info[future] = (
                        task_id,
                        task.dataset_name,
                        task.optimizer_name,
                        task.model_name,
                    )

                    checkpoint_manager.update_task_result(
                        TaskResult(
                            id=task_id,
                            dataset_name=task.dataset_name,
                            optimizer_name=task.optimizer_name,
                            model_name=task.model_name,
                            status=TASK_STATUS_PENDING,
                            timestamp_start=time.time(),
                        )
                    )
                    self.benchmark_logger.update_active_task_status(
                        future=future,
                        dataset_name=task.dataset_name,
                        optimizer_name=task.optimizer_name,
                        model_name=task.model_name,
                        status=TASK_STATUS_PENDING,
                    )
                    live.update(self.benchmark_logger._generate_live_display_message())

                # Process completions as they happen
                running_futures: set[Future[TaskResult]] = set()
                completed_futures: set[Future[TaskResult]] = set()

                def update_running_tasks() -> None:
                    """Check for and mark newly running tasks"""
                    # Only mark up to max_workers tasks as running
                    slots_available = self.max_workers - len(running_futures)
                    if slots_available <= 0:
                        return

                    current_running = [
                        f
                        for f in future_to_info
                        if f.running()
                        and f not in running_futures
                        and f not in completed_futures
                    ]
                    # Limit to available slots to prevent race conditions
                    for running_future in current_running[:slots_available]:
                        (
                            tid,
                            dn,
                            on,
                            mn,
                        ) = future_to_info[running_future]
                        running_futures.add(running_future)
                        checkpoint_manager.update_task_result(
                            TaskResult(
                                id=tid,
                                dataset_name=dn,
                                optimizer_name=on,
                                model_name=mn,
                                status=TASK_STATUS_RUNNING,
                                timestamp_start=time.time(),
                            )
                        )
                        self.benchmark_logger.update_active_task_status(
                            future=running_future,
                            dataset_name=dn,
                            optimizer_name=on,
                            model_name=mn,
                            status=TASK_STATUS_RUNNING,
                        )
                        live.update(
                            self.benchmark_logger._generate_live_display_message()
                        )

                try:
                    pending_futures = set(future_to_info.keys())

                    while pending_futures:
                        # Update running tasks display
                        update_running_tasks()

                        # Wait for at least one task to complete (with timeout for UI updates)
                        done, pending_futures = wait(
                            pending_futures, timeout=1.0, return_when=FIRST_COMPLETED
                        )

                        # Process all completed tasks
                        for future in done:
                            (
                                task_id,
                                dataset_name,
                                optimizer_name,
                                model_name,
                            ) = future_to_info[future]
                            completed_futures.add(future)
                            running_futures.discard(future)  # Remove from running set

                            try:
                                result = future.result()
                                task_results.append(result)
                                checkpoint_manager.update_task_result(result)
                                self.benchmark_logger.update_active_task_status(
                                    future=future,
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    model_name=model_name,
                                    status=result.status,
                                )
                            except Exception:
                                result = TaskResult(
                                    id=task_id,
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    model_name=model_name,
                                    status=TASK_STATUS_FAILED,
                                    timestamp_start=time.time(),
                                    initial_prompt=None,
                                    error_message=traceback.format_exc(),
                                )
                                checkpoint_manager.update_task_result(result)
                                self.benchmark_logger.update_active_task_status(
                                    future=future,
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    model_name=model_name,
                                    status=TASK_STATUS_FAILED,
                                )

                            self.benchmark_logger.add_result_panel(
                                dataset_name=dataset_name,
                                optimizer_name=optimizer_name,
                                task_detail_data=result,
                            )
                            self.benchmark_logger.remove_active_task_status(
                                future, final_status=result.status
                            )
                            live.update(
                                self.benchmark_logger._generate_live_display_message()
                            )
                except KeyboardInterrupt:
                    executor.shutdown(wait=False, cancel_futures=True)
                    sys.exit(1)

        total_duration = time.time() - start_time
        self.benchmark_logger.print_benchmark_footer(
            results=task_results,
            total_duration=total_duration,
        )
