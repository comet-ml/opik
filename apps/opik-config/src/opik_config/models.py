from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass
class ConfigValue:
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
