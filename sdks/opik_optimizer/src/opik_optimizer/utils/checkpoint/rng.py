"""
Utilities for serializing and restoring RNG states.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass
from typing import Any

try:
    import numpy as np
except ImportError:  # pragma: no cover - numpy optional in some installs
    np = None  # type: ignore[assignment]


@dataclass(slots=True)
class RNGSnapshot:
    """Serializable RNG payload."""

    random_state: Any
    numpy_state: Any

    def fingerprint(self) -> str:
        """Produce a stable fingerprint for metadata."""
        return json.dumps(
            {
                "random": self.random_state,
                "numpy": self.numpy_state[1] if self.numpy_state else None,
            },
            sort_keys=True,
        )


def capture_rng_state() -> RNGSnapshot:
    """Capture random and numpy RNG states."""
    random_state = random.getstate()
    numpy_state = None
    if np is not None:  # type: ignore[attr-defined]
        np_state = np.random.get_state()  # type: ignore[attr-defined]
        numpy_state = (
            np_state[0],
            np_state[1].tolist(),
            np_state[2],
            np_state[3],
            np_state[4],
        )
    return RNGSnapshot(random_state=random_state, numpy_state=numpy_state)


def restore_rng_state(snapshot: RNGSnapshot) -> None:
    """Restore random and numpy RNG states."""
    random.setstate(snapshot.random_state)
    if np is not None and snapshot.numpy_state is not None:  # type: ignore[attr-defined]
        kind, keys, pos, has_gauss, cached_gaussian = snapshot.numpy_state
        np.random.set_state(  # type: ignore[attr-defined]
            (
                kind,
                np.array(keys, dtype="uint32"),
                pos,
                has_gauss,
                cached_gaussian,
            )
        )
