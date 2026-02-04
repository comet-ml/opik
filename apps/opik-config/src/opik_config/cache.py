"""ContextVar-based per-run cache for resolved configurations."""

from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any


@dataclass
class ResolvedConfig:
    """Cached resolution result for a single config class."""
    values: dict[str, Any] = field(default_factory=dict)  # field_name -> resolved value
    value_ids: dict[str, str] = field(default_factory=dict)  # field_name -> backend value ID
    assigned_variant: str | None = None
    experiment_type: str | None = None
    revision: int = 0


@dataclass
class ConfigContext:
    """Context for config resolution - controls bucketing and variants."""
    project_id: str = "default"
    env: str = "prod"  # environment (e.g., "prod", "staging")
    mask_id: str | None = None  # experiment/variant identifier
    unit_id: str | None = None  # for bucketing (auto-derived from trace_id if not set)


@dataclass
class RunCache:
    """Per-run cache storing resolved configs."""
    # Key: (project_id, mask_id, class_qualname)
    configs: dict[tuple[str, str | None, str], ResolvedConfig] = field(default_factory=dict)

    def get(self, project_id: str, mask_id: str | None, qualname: str) -> ResolvedConfig | None:
        return self.configs.get((project_id, mask_id, qualname))

    def set(self, project_id: str, mask_id: str | None, qualname: str, config: ResolvedConfig) -> None:
        self.configs[(project_id, mask_id, qualname)] = config


# ContextVars for per-request isolation
_run_cache: ContextVar[RunCache | None] = ContextVar("run_cache", default=None)
_config_context: ContextVar[ConfigContext | None] = ContextVar("config_context", default=None)


def get_run_cache() -> RunCache:
    """Get or create the run cache for this context."""
    cache = _run_cache.get()
    if cache is None:
        cache = RunCache()
        _run_cache.set(cache)
    return cache


def get_config_context() -> ConfigContext:
    """Get the current config context, or a default one."""
    ctx = _config_context.get()
    if ctx is None:
        return ConfigContext()
    return ctx


def set_config_context(ctx: ConfigContext) -> None:
    """Set the config context for this run."""
    _config_context.set(ctx)


def clear_config_context() -> None:
    """Clear the config context."""
    _config_context.set(None)
