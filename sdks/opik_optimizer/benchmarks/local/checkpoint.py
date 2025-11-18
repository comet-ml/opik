import json
import os
import time
from typing import Any

from benchmark_task import TaskResult
from opik_optimizer import ChatPrompt


class ChatPromptEncoder(json.JSONEncoder):
    def default(self, obj: Any) -> Any:
        if isinstance(obj, ChatPrompt):
            return obj.to_dict()
        return super().default(obj)


class BenchmarkCheckpointManager:
    def __init__(
        self,
        checkpoint_folder: str,
        run_id: str,
        test_mode: bool,
        demo_datasets: list[str],
        optimizers: list[str],
        models: list[str],
    ):
        self.checkpoint_timestamp = time.time()
        self.checkpoint_folder = os.path.abspath(checkpoint_folder)
        os.makedirs(self.checkpoint_folder, exist_ok=True)

        self.run_id = run_id
        self.checkpoint_file = os.path.join(self.checkpoint_folder, "checkpoint.json")
        os.makedirs(os.path.dirname(self.checkpoint_file), exist_ok=True)

        self.test_mode = test_mode
        self.demo_datasets = demo_datasets
        self.optimizers = optimizers
        self.models = models

        self.task_results: list[TaskResult] = []

    def save(self) -> None:
        with open(self.checkpoint_file, "w") as f:
            checkpoint_dict = {
                "run_id": self.run_id,
                "test_mode": self.test_mode,
                "demo_datasets": self.demo_datasets,
                "optimizers": self.optimizers,
                "models": self.models,
                "task_results": [x.model_dump() for x in self.task_results],
            }
            json.dump(checkpoint_dict, f, cls=ChatPromptEncoder, indent=3)

    def load(self) -> None:
        if not os.path.exists(self.checkpoint_file):
            raise FileNotFoundError(
                f"Checkpoint file {self.checkpoint_file} does not exist"
            )

        with open(self.checkpoint_file) as f:
            checkpoint_data = json.load(f)

            self.test_mode = checkpoint_data["test_mode"]
            self.demo_datasets = checkpoint_data["demo_datasets"]
            self.optimizers = checkpoint_data["optimizers"]
            self.models = checkpoint_data["models"]
            self.task_results = [
                TaskResult.model_validate(x) for x in checkpoint_data["task_results"]
            ]

    def update_task_result(self, task_result: TaskResult) -> None:
        # Append or replace the task result
        if task_result.id in [x.id for x in self.task_results]:
            self.task_results = [x for x in self.task_results if x.id != task_result.id]
        self.task_results.append(task_result)

        # Save the benchmark checkpoint
        self.save()
