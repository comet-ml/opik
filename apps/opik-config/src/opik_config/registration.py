"""Non-blocking key registration with background worker."""

from __future__ import annotations

import atexit
import threading
import time
from dataclasses import dataclass, field
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from .client import ConfigClient


@dataclass
class KeyMetadata:
    """Metadata for a config key to register with the backend."""
    key: str
    type_hint: str
    default_value: Any
    class_name: str
    field_name: str


@dataclass
class RegistrationQueue:
    """Thread-safe queue for pending registrations."""
    pending: list[KeyMetadata] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)
    client: "ConfigClient | None" = None
    project_id: str = "default"
    _worker_started: bool = False
    _shutdown: bool = False


_queue = RegistrationQueue()
_worker_thread: threading.Thread | None = None


def queue_registration(metadata: KeyMetadata) -> None:
    """Add a key to the registration queue (non-blocking)."""
    with _queue.lock:
        _queue.pending.append(metadata)
        _ensure_worker_started()


def set_registration_client(client: "ConfigClient", project_id: str = "default") -> None:
    """Set the client to use for registration."""
    with _queue.lock:
        _queue.client = client
        _queue.project_id = project_id


def flush_registrations() -> None:
    """Immediately flush all pending registrations."""
    with _queue.lock:
        if not _queue.pending or not _queue.client:
            return
        pending = _queue.pending[:]
        _queue.pending.clear()
        client = _queue.client
        project_id = _queue.project_id

    if pending and client:
        try:
            client.register_keys(project_id, pending)
        except Exception:
            pass  # Best-effort, don't crash


def _worker_loop() -> None:
    """Background worker that periodically flushes registrations."""
    while not _queue._shutdown:
        time.sleep(0.5)  # Batch registrations every 500ms
        flush_registrations()


def _ensure_worker_started() -> None:
    """Start the background worker if not already running."""
    global _worker_thread
    if _queue._worker_started:
        return
    _queue._worker_started = True
    _worker_thread = threading.Thread(target=_worker_loop, daemon=True)
    _worker_thread.start()


def _shutdown_worker() -> None:
    """Shutdown the background worker and flush remaining registrations."""
    _queue._shutdown = True
    flush_registrations()


atexit.register(_shutdown_worker)
