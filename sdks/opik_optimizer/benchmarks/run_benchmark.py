import argparse
from typing import List, Optional

import benchmark_config
import benchmark_runner
import validation

DEFAULT_MAX_WORKERS: int = 3
DEFAULT_SEED: int = 42
DEFAULT_CHECKPOINT_DIR: str = "./benchmark_results"


def run_benchmark(
    demo_datasets: Optional[List[str]] = None,
    optimizers: Optional[List[str]] = None,
    models: Optional[List[str]] = None,
    max_workers: int = DEFAULT_MAX_WORKERS,
    seed: int = DEFAULT_SEED,
    test_mode: bool = False,
    checkpoint_dir: str = DEFAULT_CHECKPOINT_DIR,
    retry_failed_run_id: Optional[str] = None,
    resume_run_id: Optional[str] = None,
) -> None:
    if demo_datasets is not None and not isinstance(demo_datasets, list):
        raise ValueError("demo_datasets must be a list of strings")

    if optimizers is not None and not isinstance(optimizers, list):
        raise ValueError("optimizers must be a list of strings")

    if models is not None and not isinstance(models, list):
        raise ValueError("models must be a list of strings")

    # To avoid running many benchmarks, confirm the user actions
    validation.ask_for_input_confirmation(
        demo_datasets=demo_datasets,
        optimizers=optimizers,
        test_mode=test_mode,
        retry_failed_run_id=retry_failed_run_id,
        resume_run_id=resume_run_id,
    )

    # Get default configurations
    if demo_datasets is None:
        demo_datasets = list(benchmark_config.DATASET_CONFIG.keys())

    if optimizers is None:
        optimizers = list(benchmark_config.OPTIMIZER_CONFIGS.keys())

    if models is None:
        models = benchmark_config.MODELS

    runner = benchmark_runner.BenchmarkRunner(
        max_workers=max_workers,
        seed=seed,
        test_mode=test_mode,
        checkpoint_dir=checkpoint_dir,
    )

    runner.run_benchmarks(
        demo_datasets, optimizers, models, retry_failed_run_id, resume_run_id
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Run benchmarks for prompt optimization"
    )
    parser.add_argument(
        "--demo-datasets",
        type=str,
        nargs="*",
        default=None,
        help=f"Space-separated list of dataset keys to run. Available: {list(benchmark_config.DATASET_CONFIG.keys())}",
    )
    parser.add_argument(
        "--optimizers",
        type=str,
        nargs="*",
        default=None,
        help=f"Space-separated list of optimizer keys to run. Available: {list(benchmark_config.OPTIMIZER_CONFIGS.keys())}",
    )
    parser.add_argument(
        "--models",
        type=str,
        nargs="*",
        default=None,
        help=f"Space-separated list of model keys to run. Available: {list(benchmark_config.MODELS)}",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        default=False,
        help="Run in test mode with 5 examples per dataset",
    )
    parser.add_argument(
        "--seed", type=int, default=DEFAULT_SEED, help="Random seed for reproducibility"
    )

    parser.add_argument(
        "--checkpoint-dir",
        type=str,
        default=DEFAULT_CHECKPOINT_DIR,
        help="Directory to save benchmark results",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=DEFAULT_MAX_WORKERS,
        help="Maximum number of worker threads",
    )

    parser.add_argument(
        "--retry-failed-run-id",
        type=str,
        default=None,
        metavar="RUN_ID",
        help="Specify a previous RUN_ID to retry only its failed tasks. Successful tasks from that run will be skipped.",
    )
    parser.add_argument(
        "--resume-run-id",
        type=str,
        default=None,
        metavar="RUN_ID",
        help="Specify a previous RUN_ID to resume from. Successful tasks from that run will be skipped.",
    )
    args = parser.parse_args()

    run_benchmark(
        demo_datasets=args.demo_datasets,
        optimizers=args.optimizers,
        models=args.models,
        max_workers=args.max_workers,
        seed=args.seed,
        test_mode=args.test_mode,
        checkpoint_dir=args.checkpoint_dir,
        retry_failed_run_id=args.retry_failed_run_id,
        resume_run_id=args.resume_run_id,
    )
