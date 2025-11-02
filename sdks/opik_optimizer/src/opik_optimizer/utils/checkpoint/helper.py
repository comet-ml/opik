"""
High-level checkpoint read/write helpers.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List
import json

from .config import CheckpointConfig
from .rng import RNGSnapshot, capture_rng_state, restore_rng_state
from .schema import CheckpointMetadata, CheckpointState
from .storage import LocalStorageProvider, StorageProvider, compute_sha256, dumps_json


@dataclass(slots=True)
class CheckpointBundle:
    """In-memory representation of checkpoint components."""

    metadata: CheckpointMetadata
    state: CheckpointState
    rng_snapshot: RNGSnapshot
    digest: str
    path: Path


class CheckpointHelper:
    """
    Manages serialization and deserialization of optimizer checkpoints.
    """

    def __init__(
        self,
        config: CheckpointConfig,
        storage: StorageProvider | None = None,
    ) -> None:
        self.config = config
        self.storage = storage or LocalStorageProvider(config.compression)

    def save(
        self,
        optimizer_name: str,
        optimizer_version: str,
        round_index: int,
        base_state: Dict[str, Any],
        optimizer_state: Dict[str, Any],
        *,
        project_name: str | None = None,
        dataset_id: str | None = None,
        dataset_name: str | None = None,
        dataset_checksum: str | None = None,
    ) -> CheckpointBundle:
        """Persist a checkpoint bundle to disk."""
        run_id = base_state.get("run_id") or uuid.uuid4().hex
        rng_snapshot = capture_rng_state()
        metadata = CheckpointMetadata(
            optimizer=optimizer_name,
            optimizer_version=optimizer_version,
            project_name=project_name,
            dataset_id=dataset_id,
            dataset_name=dataset_name,
            dataset_checksum=dataset_checksum,
            run_id=run_id,
            round_index=round_index,
            random_state_fingerprint=rng_snapshot.fingerprint(),
        )
        state = CheckpointState(base_state=base_state, optimizer_state=optimizer_state)

        meta_bytes = dumps_json(json.loads(metadata.model_dump_json()))
        state_bytes = dumps_json(json.loads(state.model_dump_json()))
        digest = compute_sha256(meta_bytes, state_bytes)

        target_dir = (
            self.config.resolve_directory()
            / optimizer_name
            / metadata.run_id
        )
        suffix = ".json.gz" if self.config.compression == "gzip" else ".json"
        filename = f"round-{round_index:04d}{suffix}"
        path = target_dir / filename
        payload = json.dumps(
            {
                "metadata": json.loads(metadata.model_dump_json()),
                "state": json.loads(state.model_dump_json()),
                "digest": digest,
                "rng": {
                    "random_state": rng_snapshot.random_state,
                    "numpy_state": rng_snapshot.numpy_state,
                },
            },
            sort_keys=True,
        ).encode("utf-8")
        self.storage.save(path, payload)

        return CheckpointBundle(
            metadata=metadata,
            state=state,
            rng_snapshot=rng_snapshot,
            digest=digest,
            path=path,
        )

    def load(self, path: Path) -> CheckpointBundle:
        """Load a checkpoint bundle from disk."""
        payload = self.storage.load(path)
        data = json.loads(payload.decode("utf-8"))

        def _convert_random_state(obj: Any) -> Any:
            if isinstance(obj, list):
                return tuple(_convert_random_state(x) for x in obj)
            return obj

        metadata = CheckpointMetadata.model_validate(data["metadata"])
        state = CheckpointState.model_validate(data["state"])
        rng_snapshot = RNGSnapshot(
            random_state=_convert_random_state(data["rng"]["random_state"]),
            numpy_state=tuple(data["rng"]["numpy_state"])
            if data["rng"]["numpy_state"] is not None
            else None,
        )
        digest = data["digest"]

        meta_bytes = dumps_json(json.loads(metadata.model_dump_json()))
        state_bytes = dumps_json(json.loads(state.model_dump_json()))
        if compute_sha256(meta_bytes, state_bytes) != digest:
            raise ValueError(f"Checkpoint digest mismatch for {path}")

        restore_rng_state(rng_snapshot)
        return CheckpointBundle(
            metadata=metadata,
            state=state,
            rng_snapshot=rng_snapshot,
            digest=digest,
            path=path,
        )

    def list_checkpoints(self, optimizer_name: str, run_id: str) -> List[Path]:
        run_dir = self.config.resolve_directory() / optimizer_name / run_id
        if not run_dir.exists():
            return []
        checkpoints = sorted(run_dir.glob("round-*.json*"))
        return checkpoints

    def prune_old_checkpoints(
        self, optimizer_name: str, run_id: str, max_to_keep: int
    ) -> None:
        checkpoints = self.list_checkpoints(optimizer_name, run_id)
        if len(checkpoints) <= max_to_keep:
            return
        to_remove = checkpoints[:-max_to_keep]
        for checkpoint_path in to_remove:
            try:
                checkpoint_path.unlink()
            except FileNotFoundError:  # pragma: no cover - best effort
                continue


def save_checkpoint_bundle(
    config: CheckpointConfig, *args: Any, **kwargs: Any
) -> CheckpointBundle:  # pragma: no cover - thin wrapper
    helper = CheckpointHelper(config)
    return helper.save(*args, **kwargs)


def load_checkpoint_bundle(
    config: CheckpointConfig,
    path: Path,
) -> CheckpointBundle:  # pragma: no cover - thin wrapper
    helper = CheckpointHelper(config)
    return helper.load(path)
