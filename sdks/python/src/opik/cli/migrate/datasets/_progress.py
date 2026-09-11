"""Shared progress-callback type aliases for the dataset migrate cascades.

All three dataset-level cascade phases -- ``version_replay``,
``optimizations``, ``experiments`` -- expose progress hooks with the
same `` (completed: int, total: int, label: str) -> None`` shape so the
executor can drive Rich progress bars without each cascade module
needing to know about Rich. Centralizing the type alias here removes
the previous drift surface (three separate definitions: two named, one
inline) and gives a single source of truth.

``label == "done"`` is the cross-cascade convention for the final
tick; consumers use it to switch the bar to a 100% / "done" rendering
and to drop the mid-loop ``(N/total)`` suffix.
"""

from __future__ import annotations

from typing import Callable

# Outer progress: ``(completed, total, label)`` -- fired once per work
# item (version, optimization, experiment). ``completed`` is the count
# done so far when the callback fires (so a mid-loop ``completed + 1``
# matches "currently processing the next item"). ``label == "done"``
# signals the final tick with ``completed == total``.
ProgressCallback = Callable[[int, int, str], None]

# Inner progress: same shape as ``ProgressCallback`` but ticks within a
# single outer item (only used by the experiment cascade today, where
# each experiment has multiple read/write phases). Aliased separately
# so the per-call-site intent is documented at the type level even
# though the underlying signature is identical.
InnerProgressCallback = Callable[[int, int, str], None]
