"""Utility helpers exposed as part of the opik_optimizer package."""

from typing import List

from .core import *  # noqa: F401,F403
from .dataset_utils import *  # noqa: F401,F403
from .prompt_segments import *  # noqa: F401,F403
from .candidate_utils import *  # noqa: F401,F403

_image_helpers = None
try:  # Pillow is optional; guard image helpers
    from .image_helpers import *  # noqa: F401,F403
except ModuleNotFoundError as exc:  # pragma: no cover - optional dependency guard
    if exc.name != "PIL":
        raise
    _image_helpers = None
else:
    from . import image_helpers as _image_helpers

from . import core as _core
from . import dataset_utils as _dataset_utils
from . import prompt_segments as _prompt_segments
from . import candidate_utils as _candidate_utils

_image_helpers_all: List[str] = (
    getattr(_image_helpers, "__all__", []) if _image_helpers is not None else []
)

__all__: list[str] = [
    *getattr(_core, "__all__", []),
    *getattr(_dataset_utils, "__all__", []),
    *getattr(_prompt_segments, "__all__", []),
    *getattr(_candidate_utils, "__all__", []),
    *_image_helpers_all,
]
