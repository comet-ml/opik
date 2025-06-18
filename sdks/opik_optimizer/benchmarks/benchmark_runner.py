import os
import sys
import time
import traceback
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from typing import List, Optional

import benchmark_checkpoint
import benchmark_config
import benchmark_logging
from benchmark_task import TaskEvaluationResult, TaskResult

import opik_optimizer
import opik_optimizer.datasets
from opik_optimizer import (
    AgentConfig,
    OptimizableAgent,
    BaseOptimizer,
    reporting_utils,
)
from opik_optimizer.optimization_config import chat_prompt


@benchmark_logging.log_console_output_to_file()
def run_optimization(
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    test_mode: bool,
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
            optimizer: BaseOptimizer = getattr(
                opik_optimizer, optimizer_config.class_name
            )(model=model_name, **optimizer_config.params)

            messages = benchmark_config.INITIAL_PROMPTS[dataset_name]

            class LiteLLMAgent(OptimizableAgent):
                model = model_name

            agent_class = LiteLLMAgent
            agent_config = AgentConfig(
                chat_prompt=chat_prompt.ChatPrompt(messages=messages)  # type: ignore
            )
            agent = agent_class(agent_config)
            # Start by running a first evaluation
            start_time_initial_eval = time.time()
            initial_evaluation = []
            for metric_ in dataset_config.metrics:
                result = agent.evaluate(dataset=dataset, metric=metric_, n_threads=4)
                initial_evaluation.append(
                    {
                        "metric_name": metric_.__name__,
                        "score": result,
                        "timestamp": time.time(),
                    }
                )
            initial_evaluation_duration = time.time() - start_time_initial_eval

            # Run optimization
            optimization_results = optimizer.optimize_agent(
                agent_class=agent_class,
                agent_config=agent_config,
                dataset=dataset,
                metric=dataset_config.metrics[0],
            )

            new_agent_config = AgentConfig(
                chat_prompt=chat_prompt.ChatPrompt(messages=optimization_results.prompt)
            )
            new_agent = agent_class(new_agent_config)

            # Run final evaluation
            start_time_final_eval = time.time()
            optimized_evaluation = []
            for metric_ in dataset_config.metrics:
                result = new_agent.evaluate(
                    dataset=dataset, metric=metric_, n_threads=4
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
                status="Success",
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
                status="Failed",
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                error_message=traceback.format_exc(),
                timestamp_end=time.time(),
            )


class BenchmarkRunner:
    run_id: Optional[str] = None

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
        demo_datasets: List[str],
        optimizers: List[str],
        models: List[str],
        retry_failed_run_id: Optional[str],
        resume_run_id: Optional[str],
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

        # Initialize logger
        checkpoint_folder = os.path.join(self.checkpoint_dir, self.run_id)
        self.benchmark_logger.setup_logger(
            demo_datasets, optimizers, models, self.test_mode, self.run_id
        )
        self.benchmark_logger.print_benchmark_header()

        # Initialize BenchmarkCheckpointManager
        checkpoint_manager = benchmark_checkpoint.BenchmarkCheckpointManager(
            checkpoint_folder=checkpoint_folder,
            run_id=self.run_id,
            test_mode=self.test_mode,
            demo_datasets=demo_datasets,
            optimizers=optimizers,
            models=models,
        )
        if resume_run_id or retry_failed_run_id:
            checkpoint_manager.load()
        else:
            checkpoint_manager.save()

        # Start scheduling the tasks
        start_time = time.time()
        task_results: List[TaskResult] = []
        with self.benchmark_logger.create_live_panel() as live:
            live.update(self.benchmark_logger._generate_live_display_message())

            with ProcessPoolExecutor(max_workers=self.max_workers) as executor:
                futures = []

                for dataset_name in demo_datasets:
                    for optimizer_name in optimizers:
                        for model_name in models:
                            task_id = f"{dataset_name}_{optimizer_name}_{model_name}"

                            # If retrying failed runs, skip tasks that have not failed
                            if retry_failed_run_id:
                                failed_tasks = [
                                    x.id
                                    for x in checkpoint_manager.task_results
                                    if x.status == "Failed"
                                ]

                                if task_id not in failed_tasks:
                                    continue

                            # If resuming a run, skip tasks that have already been completed
                            if resume_run_id:
                                completed_tasks = [
                                    x.id
                                    for x in checkpoint_manager.task_results
                                    if x.status != "Pending"
                                ]

                                if task_id in completed_tasks:
                                    continue

                            # Schedule the task
                            future = executor.submit(
                                run_optimization,
                                task_id=task_id,
                                dataset_name=dataset_name,
                                optimizer_name=optimizer_name,
                                model_name=model_name,
                                test_mode=self.test_mode,
                                checkpoint_folder=checkpoint_folder,
                            )
                            futures.append(
                                (
                                    task_id,
                                    future,
                                    dataset_name,
                                    optimizer_name,
                                    model_name,
                                )
                            )

                            # Update the checkpoint manager
                            checkpoint_manager.update_task_result(
                                TaskResult(
                                    id=task_id,
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    model_name=model_name,
                                    status="Pending",
                                    timestamp_start=time.time(),
                                )
                            )

                            # Update the logging
                            self.benchmark_logger.update_active_task_status(
                                future=future,
                                dataset_name=dataset_name,
                                optimizer_name=optimizer_name,
                                model_name=model_name,
                                status="Pending",
                            )
                            live.update(
                                self.benchmark_logger._generate_live_display_message()
                            )

                completed_futures: List[str] = []
                while True:
                    try:
                        for future_info in futures:
                            (
                                task_id,
                                future,
                                dataset_name,
                                optimizer_name,
                                model_name,
                            ) = future_info
                            # If the task has not completed and is now running, mark it as Running.
                            if not future.done() and future.running():
                                # Update the checkpoint manager
                                checkpoint_manager.update_task_result(
                                    TaskResult(
                                        id=task_id,
                                        dataset_name=dataset_name,
                                        optimizer_name=optimizer_name,
                                        model_name=model_name,
                                        status="Running",
                                        timestamp_start=time.time(),
                                    )
                                )

                                # Update the logging
                                self.benchmark_logger.update_active_task_status(
                                    future=future,
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    model_name=model_name,
                                    status="Running",
                                )
                            elif future.done():
                                if task_id in completed_futures:
                                    continue
                                try:
                                    result = future.result()
                                    task_results.append(result)
                                    completed_futures.append(task_id)

                                    # Update the checkpoint manager
                                    checkpoint_manager.update_task_result(result)

                                    # Update the logging
                                    self.benchmark_logger.update_active_task_status(
                                        future=future,
                                        dataset_name=dataset_name,
                                        optimizer_name=optimizer_name,
                                        model_name=model_name,
                                        status=result.status,
                                    )
                                except Exception:
                                    result = TaskResult(
                                        id=f"{dataset_name}_{optimizer_name}_{model_name}",
                                        dataset_name=dataset_name,
                                        optimizer_name=optimizer_name,
                                        model_name=model_name,
                                        status="Failed",
                                        timestamp_start=time.time(),
                                        initial_prompt=None,
                                        error_message=traceback.format_exc(),
                                    )
                                    completed_futures.append(task_id)

                                    # Update the checkpoint manager
                                    checkpoint_manager.update_task_result(result)

                                    # Update the logging
                                    self.benchmark_logger.update_active_task_status(
                                        future=future,
                                        dataset_name=dataset_name,
                                        optimizer_name=optimizer_name,
                                        model_name=model_name,
                                        status="Failed",
                                    )

                                self.benchmark_logger.add_result_panel(
                                    dataset_name=dataset_name,
                                    optimizer_name=optimizer_name,
                                    task_detail_data=result,
                                )
                            live.update(
                                self.benchmark_logger._generate_live_display_message()
                            )
                    except KeyboardInterrupt:
                        executor.shutdown(wait=False, cancel_futures=True)
                        sys.exit(1)

                    if all(future.done() for _, future, _, _, _ in futures) and len(
                        completed_futures
                    ) == len(futures):
                        break
                    time.sleep(50)

        total_duration = time.time() - start_time
        self.benchmark_logger.print_benchmark_footer(
            results=task_results,
            total_duration=total_duration,
        )
