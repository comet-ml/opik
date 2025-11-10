"""Utility helpers exposed as part of the opik_optimizer package."""

from types import ModuleType

from . import core as _core
from . import dataset_utils as _dataset_utils
from . import candidate_utils as _candidate_utils

from .core import *  # noqa: F401,F403
from .dataset_utils import *  # noqa: F401,F403
from .candidate_utils import *  # noqa: F401,F403

_multimodal_utils: ModuleType | None
try:  # Pillow is optional; guard multimodal helpers
    from . import multimodal as _multimodal_utils  # noqa: F401
    from .multimodal import *  # noqa: F401,F403
except ModuleNotFoundError as exc:  # pragma: no cover - optional dependency guard
    if exc.name != "PIL":
        raise
    _multimodal_utils = None

_multimodal_all: list[str] = (
    getattr(_multimodal_utils, "__all__", []) if _multimodal_utils is not None else []
)

__all__: list[str] = [
    *getattr(_core, "__all__", []),
    *getattr(_dataset_utils, "__all__", []),
    *getattr(_candidate_utils, "__all__", []),
    *_multimodal_all,
]
