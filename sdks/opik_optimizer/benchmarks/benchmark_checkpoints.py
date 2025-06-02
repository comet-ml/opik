
import json
import logging
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional


@dataclass
class BenchmarkCheckpoint:
    checkpoint_timestamp: str
    run_id: str
    task_results_dir: Optional[str]
    test_mode: bool
    project_config: Dict[str, Any]
    results_summary: List[Dict[str, Any]]
    dataset_cache_keys: List[str]
    monitor_state: Dict[str, Any]
    environment: Dict[str, Any]

    @staticmethod
    def from_runner_state(
        run_id: str,
        task_results_dir: Optional[Path],
        test_mode: bool,
        project_config: Dict[str, Any],
        results_summary: List[Dict[str, Any]],
        dataset_cache_keys: List[str],
        monitor_state: Dict[str, Any],
        environment: Dict[str, Any],
        checkpoint_timestamp: Optional[str] = None,
    ) -> "BenchmarkCheckpoint":
        return BenchmarkCheckpoint(
            checkpoint_timestamp=checkpoint_timestamp or datetime.now().strftime("%Y%m%d_%H%M%S"),
            run_id=run_id,
            task_results_dir=str(task_results_dir.resolve()) if task_results_dir else None,
            test_mode=test_mode,
            project_config=project_config,
            results_summary=results_summary,
            dataset_cache_keys=dataset_cache_keys,
            monitor_state=monitor_state,
            environment=environment,
        )

    def to_dict(self) -> dict:
        return asdict(self)


class BenchmarkCheckpointManager:
    def __init__(self, checkpoint_dir: Path):
        self.checkpoint_dir = checkpoint_dir
        self.checkpoint_dir.mkdir(parents=True, exist_ok=True)
        self.logger = logging.getLogger(__name__)

    def save_checkpoint(self, checkpoint: BenchmarkCheckpoint):
        checkpoint_file = self._get_checkpoint_file_path(checkpoint.run_id, checkpoint.checkpoint_timestamp)
        try:
            with open(checkpoint_file, "w") as f:
                json.dump(checkpoint.to_dict(), f, indent=2)
            self.logger.info(f"Saved checkpoint to {checkpoint_file}")
        except Exception as e:
            self.logger.error(f"Failed to save checkpoint: {e}")

    def load_latest_checkpoint(self, resume_enabled: bool, retry_failed_run_id: Optional[str] = None) -> Optional[BenchmarkCheckpoint]:
        checkpoint_file = self._find_checkpoint_file(resume_enabled, retry_failed_run_id)
        if not checkpoint_file:
            self.logger.info("No checkpoint file found for loading.")
            return None
        try:
            with open(checkpoint_file, "r") as f:
                checkpoint_data = json.load(f)
            return BenchmarkCheckpoint(**checkpoint_data)
        except Exception as e:
            self.logger.error(f"Failed to load checkpoint: {e}")
            return None

    def _get_checkpoint_file_path(self, run_id: str, timestamp: str) -> Path:
        return self.checkpoint_dir / f"checkpoint_{run_id}_{timestamp}.json"
    
    def _find_checkpoint_file(self, resume_enabled: bool, retry_failed_run_id: Optional[str]) -> Optional[Path]:
        # Logic to find the right checkpoint file (latest, or by run_id)
        checkpoint_files = list(self.checkpoint_dir.glob("checkpoint_*.json"))
        if retry_failed_run_id:
            candidates = [f for f in checkpoint_files if f"checkpoint_{retry_failed_run_id}_" in f.name]
            if candidates:
                return max(candidates, key=lambda x: x.stat().st_mtime)
        elif resume_enabled and checkpoint_files:
            return max(checkpoint_files, key=lambda x: x.stat().st_mtime)
        return None
