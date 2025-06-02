import os
import random
import time
from datetime import datetime
from typing import List, Optional

from concurrent.futures import ProcessPoolExecutor, as_completed
from . import benchmark_logging


def run_optimization(
    dataset_name: str,
    optimizer_name: str,
    model_name: str
):
    time.sleep(random.randint(1, 5))
    return 0.12
    pass

class BenchmarkRunner():
    run_id: Optional[str] = None

    def __init__(
        self,
        max_workers: int,
        seed: int,
        test_mode: bool
    ):
        self.max_workers = max_workers
        self.seed = seed
        self.test_mode = test_mode
        self.benchmark_logger = benchmark_logging.BenchmarkLogger()



    def run_benchmarks(
        self,
        demo_datasets: List[str],
        optimizers: List[str],
        models: List[str],
        retry_failed_run_id: Optional[str],
        resume_run_id: Optional[str]
    ):
        # Create unique id
        self.run_id = f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{os.urandom(4).hex()}"

        # Initialize logger
        self.benchmark_logger.setup_logger(
            demo_datasets,
            optimizers,
            models,
            self.test_mode,
            self.run_id
        )
        self.benchmark_logger.print_benchmark_header()        
        
        with self.benchmark_logger.create_live_panel() as live:
            live.update(self.benchmark_logger._generate_live_display_message())

            with ProcessPoolExecutor(max_workers=self.max_workers) as executor:
                futures = []

                for dataset_name in demo_datasets:
                    for optimizer_name in optimizers:
                        for model_name in models:
                            future = executor.submit(
                                run_optimization,
                                dataset_name=dataset_name,
                                optimizer_name=optimizer_name,
                                model_name=model_name
                            )
                            futures.append(future)

                            self.benchmark_logger.update_active_task_status(
                                future=future,
                                task_description=f"{dataset_name}/{optimizer_name}/{model_name}",
                                optimizer_name=optimizer_name,
                                model_name=model_name,
                                status="Pending"
                            )

                            live.update(self.benchmark_logger._generate_live_display_message())
                
                for future_item in as_completed(futures):
                    try:
                        result = future_item.result()
                        self.benchmark_logger.update_active_task_status(
                            future=future_item,
                            task_description=f"{dataset_name}/{optimizer_name}/{model_name}",
                            optimizer_name=optimizer_name,
                            model_name=model_name,
                            status="Success"
                        )
                    except Exception as e:
                        self.benchmark_logger.update_active_task_status(
                            future=future_item,
                            task_description=f"{dataset_name}/{optimizer_name}/{model_name}",
                            optimizer_name=optimizer_name,
                            model_name=model_name,
                            status="Failed"
                        )
                    
                    live.update(self.benchmark_logger._generate_live_display_message())


