from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Iterable, Optional, Tuple

import opik

Metadata = Dict[str, Any]


@dataclass(frozen=True)
class RetrievalTrackingConfig:
    provider: str
    operation_paths: Tuple[str, ...]
    project_name: Optional[str] = None


def _resolve_target_and_method(root: Any, path: str) -> Tuple[Optional[Any], Optional[str]]:
    parts = path.split(".")
    target = root
    for part in parts[:-1]:
        target = getattr(target, part, None)
        if target is None:
            return None, None
    return target, parts[-1]


def _build_metadata(provider: str, operation: str) -> Metadata:
    return {
        "created_from": provider,
        "opik.kind": "retrieval",
        "opik.provider": provider,
        "opik.operation": operation,
    }


def _patch_operation(
    target: Any,
    method_name: str,
    provider: str,
    operation_name: str,
    project_name: Optional[str],
) -> bool:
    method = getattr(target, method_name, None)
    if method is None or not callable(method):
        return False

    if hasattr(method, "opik_tracked"):
        return False

    decorator = opik.track(
        name=f"{provider}.{operation_name}",
        type="tool",
        tags=[provider, "retrieval"],
        metadata=_build_metadata(provider, operation_name),
        project_name=project_name,
    )
    setattr(target, method_name, decorator(method))
    return True


def patch_retrieval_client(target: Any, config: RetrievalTrackingConfig) -> Any:
    if hasattr(target, "opik_tracked"):
        return target

    patched = False
    for operation_path in config.operation_paths:
        operation_target, method_name = _resolve_target_and_method(target, operation_path)
        if operation_target is None or method_name is None:
            continue

        operation_name = operation_path.split(".")[-1]
        patched = (
            _patch_operation(
                operation_target,
                method_name,
                provider=config.provider,
                operation_name=operation_name,
                project_name=config.project_name,
            )
            or patched
        )

    if patched:
        target.opik_tracked = True

    return target


def as_tuple(paths: Iterable[str]) -> Tuple[str, ...]:
    return tuple(paths)
