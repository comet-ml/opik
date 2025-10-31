"""Core optimization logic for Modal workers (without Modal decorators)."""

import time
import traceback

import benchmark_config
import opik_optimizer
import opik_optimizer.datasets
from benchmark_task import TaskEvaluationResult, TaskResult
from opik_optimizer import BaseOptimizer, reporting_utils
from opik_optimizer.optimization_config import chat_prompt


def run_optimization_task(
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    test_mode: bool,
) -> TaskResult:
    """
    Run a single optimization task.

    This is the core optimization logic without Modal decorators,
    making it easier to test and reuse.

    Args:
        task_id: Unique identifier for this task
        dataset_name: Name of dataset to use
        optimizer_name: Name of optimizer to use
        model_name: Name of model to use
        test_mode: Whether to run in test mode (5 examples)

    Returns:
        TaskResult object containing the optimization results
    """
    timestamp_start = time.time()
    initial_prompt = None
    optimized_prompt = None

    print(f"[{task_id}] Starting optimization...")

    with reporting_utils.suppress_opik_logs():
        try:
            # Initialize the dataset, optimizer, metrics and initial_prompt
            dataset_config = benchmark_config.DATASET_CONFIG[dataset_name]
            dataset = getattr(opik_optimizer.datasets, dataset_name)(
                test_mode=test_mode
            )

            optimizer_config = benchmark_config.OPTIMIZER_CONFIGS[optimizer_name]
            optimizer: BaseOptimizer = getattr(
                opik_optimizer, optimizer_config.class_name
            )(model=model_name, **optimizer_config.params)

            messages = benchmark_config.INITIAL_PROMPTS[dataset_name]
            initial_prompt = chat_prompt.ChatPrompt(messages=messages)  # type: ignore

            # Run initial evaluation
            print(f"[{task_id}] Running initial evaluation...")
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
            print(
                f"[{task_id}] Initial evaluation complete in {initial_evaluation_duration:.2f}s"
            )

            # Run optimization
            print(f"[{task_id}] Running optimization...")
            optimization_results = optimizer.optimize_prompt(
                prompt=initial_prompt,
                dataset=dataset,
                metric=dataset_config.metrics[0],
                **optimizer_config.optimize_params,
            )
            optimized_prompt = chat_prompt.ChatPrompt(
                messages=optimization_results.prompt
            )

            # Run final evaluation
            print(f"[{task_id}] Running final evaluation...")
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
            print(
                f"[{task_id}] Final evaluation complete in {optimized_evaluation_duration:.2f}s"
            )

            # Create result object
            result = TaskResult(
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

            print(
                f"[{task_id}] Completed successfully in {time.time() - timestamp_start:.2f}s"
            )
            return result

        except Exception as e:
            print(f"[{task_id}] Failed with error: {str(e)}")

            result = TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status="Failed",
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                error_message=traceback.format_exc(),
                timestamp_end=time.time(),
            )

            return result
