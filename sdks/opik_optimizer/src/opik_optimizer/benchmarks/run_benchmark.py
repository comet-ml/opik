from typing import List, Optional

from . import benchmark_config, validation, benchmark_runner


DEFAULT_MAX_WORKERS: int = 10
DEFAULT_SEED: int = 42
DEFAULT_OUTPUT_DIR: str = "benchmark_results"

def run_benchmark(
    demo_datasets: Optional[List[str]] = None,
    optimizers: Optional[List[str]] = None,
    models: Optional[List[str]] = None,
    max_workers: int = DEFAULT_MAX_WORKERS,
    seed: int= DEFAULT_SEED,
    test_mode: bool=False,
    retry_failed_run_id: Optional[str] = None,
    resume_run_id: Optional[str] = None
):
    # To avoid running many benchmarks, confirm the user actions
    validation.ask_for_input_confirmation(
        demo_datasets=demo_datasets,
        optimizers=optimizers,
        test_mode=test_mode,
        retry_failed_run_id=retry_failed_run_id,
        resume_run_id=resume_run_id
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
        test_mode=test_mode
    )

    print(optimizers)
    runner.run_benchmarks(
        demo_datasets,
        optimizers,
        models,
        retry_failed_run_id,
        resume_run_id
    )
