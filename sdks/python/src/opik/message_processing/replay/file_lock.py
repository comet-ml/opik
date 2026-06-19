import os
from typing import Optional

try:
    from filelock import FileLock, Timeout

    _HAS_FILELOCK = True
except Exception:  # pragma: no cover - defensive import
    _HAS_FILELOCK = False


def _replay_lock_path(db_file: str) -> str:
    """Return the path to the replay leader lock file for a given DB file."""
    return f"{db_file}.replay.lock"


def acquire_replay_lock(db_file: str) -> Optional[FileLock]:
    """Acquire an exclusive non-blocking lock for replay leadership.

    Uses a separate ``.replay.lock`` file next to the SQLite database so the
    lock does not interfere with SQLite's own file locking. Returns a
    :class:`filelock.FileLock` instance on success, or ``None`` if another
    process already holds the lock.

    Raises:
        RuntimeError: If the ``filelock`` package is not installed. It is a
            declared dependency of the SDK and is required for replay leader
            election when using a persistent offline database.
    """
    if not _HAS_FILELOCK:
        raise RuntimeError(
            "The 'filelock' package is required for offline replay leader election "
            "but is not installed. Please install opik with its dependencies "
            "(e.g. 'pip install opik')."
        )

    lock_path = _replay_lock_path(db_file)
    parent_dir = os.path.dirname(os.path.abspath(lock_path))
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)

    lock = FileLock(lock_path, timeout=0)
    try:
        lock.acquire()
        return lock
    except Timeout:
        return None


def release_replay_lock(lock: Optional[FileLock]) -> None:
    """Release a previously acquired replay lock."""
    if lock is None:
        return
    try:
        lock.release()
    except Exception:
        pass
