"""Shared bootstrap for the NLTK ``vader_lexicon`` corpus.

Two metrics build a VADER analyzer -- ``Sentiment`` and ``VADERSentiment`` -- and both
need the same corpus, which a fresh ``pip install nltk`` does not ship. Doing the fetch
here means one attempt per process rather than one per metric, and one place deciding
what a missing corpus looks like to a caller.
"""

from __future__ import annotations

import threading
from typing import Any, Callable

try:  # pragma: no cover - optional dependency
    import nltk
except ImportError:  # pragma: no cover - optional dependency
    nltk = None  # type: ignore[assignment]


_download_lock = threading.Lock()
_download_attempted = False


def _download_lexicon_once() -> None:
    """Fetch the ``vader_lexicon`` corpus, at most once per process.

    Every metric instance builds its own analyzer, so without this guard a process that
    cannot reach the download server (offline, or behind a proxy) would pay for a failed
    fetch on each construction. The outcome is the same either way -- the corpus is there
    or it is not -- so a single attempt is enough, and the caller reports what it finds.
    """
    global _download_attempted

    with _download_lock:
        if _download_attempted or nltk is None:
            return
        try:
            nltk.download("vader_lexicon", quiet=True)
        except Exception:
            # Whether the fetch failed or was never possible, what matters to the caller
            # is that the corpus is still missing, which it checks next.
            pass
        # Recorded after the attempt, not before: a KeyboardInterrupt or SystemExit
        # during the download propagates without reaching this line, so the process
        # can still try again rather than reporting the corpus missing forever.
        _download_attempted = True


def build_analyzer(factory: Callable[[], Any], *, error_message: str) -> Any:
    """Build a VADER analyzer, fetching its lexicon if needed.

    ``SentimentIntensityAnalyzer()`` reads the ``vader_lexicon`` corpus at construction
    time and raises a bare :class:`LookupError` when it is absent. Download it once and
    fall back to an :class:`ImportError` naming the manual command when that is not
    possible. Only the missing corpus is translated; anything else the analyzer raises
    is a real failure and reaches the caller unchanged.

    Args:
        factory: Builds the analyzer, typically ``SentimentIntensityAnalyzer``.
        error_message: What to tell a caller who cannot get the corpus.

    Returns:
        The analyzer the factory produced.

    Raises:
        ImportError: If the corpus is absent and cannot be downloaded.
    """
    try:
        return factory()
    except LookupError:
        pass

    _download_lexicon_once()

    try:
        return factory()
    except LookupError as error:
        raise ImportError(error_message) from error
