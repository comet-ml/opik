"""Data models for the config system."""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Literal


@dataclass
class ConfigValue:
    """A single config value with version history."""
    key: str
    value: Any
    version: int
    timestamp: datetime = field(default_factory=datetime.now)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "value": self.value,
            "version": self.version,
            "timestamp": self.timestamp.isoformat(),
            "metadata": self.metadata,
        }


@dataclass
class ResolveResponse:
    """Response from the resolve endpoint."""
    resolved_values: dict[str, Any]  # key -> resolved value
    resolved_value_ids: dict[str, str]  # key -> value ID for tracking
    assigned_variant: str | None
    revision: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "resolved_values": self.resolved_values,
            "resolved_value_ids": self.resolved_value_ids,
            "assigned_variant": self.assigned_variant,
            "revision": self.revision,
        }


@dataclass
class KeyMetadata:
    """Metadata about a registered config key."""
    key: str
    type_hint: str
    default_value: Any
    class_name: str
    field_name: str
    registered_at: datetime = field(default_factory=datetime.now)

    def to_dict(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "type_hint": self.type_hint,
            "default_value": self.default_value,
            "class_name": self.class_name,
            "field_name": self.field_name,
            "registered_at": self.registered_at.isoformat(),
        }


@dataclass
class ConfigBehavior:
    """Configuration for how the config system behaves."""
    on_backend_unavailable: Literal["use_fallback", "error"] = "use_fallback"
    strict_context: bool = True  # Require trace context for config access
