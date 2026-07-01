"""Connection-scoped transport resources shared by :class:`opik.Opik` handles.

The objects built here (httpx pool, REST client, message-processing chain, file
upload manager, replay manager + connection monitor, and the streamer with its
consumer threads) are properties of the *connection* ``(url, workspace, api_key,
...)`` rather than of an individual client.

Responsibilities are split so each type does one thing:

- :class:`SharedConnectionResourcesBundle` — the value object: holds the live
  transport objects and knows how to dispose them (``close``).
- :class:`ConnectionResourceManager` — the lifecycle authority: derives the
  connection identity, builds-or-reuses a bundle, ref-counts it, and decides
  when to tear it down (including at process exit).
- :class:`Lease` — a per-handle, release-once token that delegates all lifecycle
  decisions back to the manager.
"""

import atexit
import hashlib
import json
import logging
import threading
from typing import Callable, Dict, Optional, Tuple

import httpx

from .. import config as opik_config
from .. import httpx_client, rest_client_configurator
from ..file_upload import upload_manager
from ..healthcheck import connection_monitor, connection_probe
from ..message_processing import (
    message_queue,
    permissions,
    streamer,
    streamer_constructors,
)
from ..message_processing.processors import message_processors, message_processors_chain
from ..message_processing.replay import replay_manager
from ..rest_api import client as rest_api_client

LOGGER = logging.getLogger(__name__)


class SharedConnectionResourcesBundle:
    """Owns the expensive transport objects for one connection identity.

    Connection-scoped: it carries no ``project_name`` or per-call state, so it
    can back multiple :class:`opik.Opik` handles. ``close`` disposes what the
    bundle owns — the streamer's threads and the file-upload worker pool, plus
    the httpx connection pool on a durable (``flush=True``) close — so evicting a
    bundle never leaks threads. ``flush_timeout`` is the connection's configured
    drain budget, used when the process-exit hook closes the bundle.
    """

    def __init__(
        self,
        httpx_client: httpx.Client,
        rest_client: rest_api_client.OpikApi,
        message_processor: message_processors.ChainedMessageProcessor,
        file_upload_manager: upload_manager.FileUploadManager,
        replay_manager: replay_manager.ReplayManager,
        streamer: streamer.Streamer,
        flush_timeout: Optional[int],
    ) -> None:
        self.httpx_client = httpx_client
        self.rest_client = rest_client
        self.message_processor = message_processor
        self.file_upload_manager = file_upload_manager
        self.replay_manager = replay_manager
        self.streamer = streamer
        self.flush_timeout = flush_timeout

    def close(self, timeout: Optional[int], *, flush: bool) -> None:
        # Drain/stop the streamer (consumer threads, replay, batch preprocessor);
        # on flush=True it also flushes pending file uploads.
        # Closing the streamer also stops and joins the replay manager (its own
        # daemon thread), so there is no separate replay teardown to do here.
        self.streamer.close(timeout, flush=flush)
        # Stop the upload worker pool too, so eviction doesn't leave its threads
        # running. wait=flush mirrors the streamer: block for in-flight uploads
        # on a durable close, return immediately on fire-and-forget teardown.
        self.file_upload_manager.close(wait=flush)
        if flush:
            # Close the httpx pool only on a durable close, and last — after the
            # streamer has joined the replay thread and uploads have drained, so
            # no request is in flight. Each bundle owns a dedicated client (built
            # per connection identity), so this never affects another bundle.
            # flush=False is fire-and-forget: the streamer deliberately leaves
            # daemon threads to finish in-flight requests, so closing the pool
            # here would race them — leave it for GC / process-exit close_all.
            self.httpx_client.close()

    def flush(self, timeout: Optional[int]) -> None:
        """Drain the shared message queue without tearing the bundle down.

        Used when a handle releases with ``flush=True`` while other handles still
        share the bundle: the queued data is persisted now, but the transport
        stays alive for the remaining handles.
        """
        self.streamer.flush(timeout)


def _create_replay_manager(
    config: opik_config.OpikConfig, httpx_client: httpx.Client
) -> replay_manager.ReplayManager:
    probe = connection_probe.ConnectionProbe(
        base_url=config.url_override,
        client=httpx_client,
    )
    monitor = connection_monitor.OpikConnectionMonitor(
        ping_interval=config.connection_monitor_ping_interval,
        check_timeout=config.connection_monitor_check_timeout,
        probe=probe,
    )

    return replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=config.replay_batch_size,
        batch_replay_delay=config.replay_batch_replay_delay,
        tick_interval_seconds=config.replay_tick_interval,
    )


def create_connection_resources(
    config: opik_config.OpikConfig, *, use_batching: bool
) -> SharedConnectionResourcesBundle:
    """Build a full transport stack for ``config``.

    Pure construction with no cache awareness — this is the default builder that
    :class:`ConnectionResourceManager` invokes on a cache miss.
    """
    httpx_client_ = httpx_client.get(
        workspace=config.workspace,
        api_key=config.api_key,
        check_tls_certificate=config.check_tls_certificate,
        compress_json_requests=config.enable_json_request_compression,
    )
    rest_client = rest_api_client.OpikApi(
        base_url=config.url_override,
        httpx_client=httpx_client_,
    )
    rest_client._client_wrapper._timeout = (
        httpx.USE_CLIENT_DEFAULT
    )  # See https://github.com/fern-api/fern/issues/5321
    rest_client_configurator.configure(rest_client)

    max_queue_size = message_queue.calculate_max_queue_size(
        maximal_queue_size=config.maximal_queue_size,
        batch_factor=config.maximal_queue_size_batch_factor,
    )

    file_uploader = upload_manager.FileUploadManager(
        rest_client=rest_client,
        httpx_client=httpx_client_,
        worker_count=config.file_upload_background_workers,
    )

    fallback_replay = _create_replay_manager(config, httpx_client_)

    message_processor = message_processors_chain.create_message_processors_chain(
        rest_client=rest_client,
        file_upload_manager=file_uploader,
        fallback_replay_manager=fallback_replay,
        unauthorized_message_types_registry=permissions.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=config.unauthorized_message_type_retry_interval,
            max_retry_count=config.unauthorized_message_type_max_retry_count,
        ),
    )
    streamer_ = streamer_constructors.construct_online_streamer(
        file_uploader=file_uploader,
        n_consumers=config.background_workers,
        use_batching=use_batching,
        use_attachment_extraction=config.is_attachment_extraction_active,
        min_base64_embedded_attachment_size=config.min_base64_embedded_attachment_size,
        max_queue_size=max_queue_size,
        message_processor=message_processor,
        url_override=config.url_override,
        fallback_replay_manager=fallback_replay,
    )

    return SharedConnectionResourcesBundle(
        httpx_client=httpx_client_,
        rest_client=rest_client,
        message_processor=message_processor,
        file_upload_manager=file_uploader,
        replay_manager=fallback_replay,
        streamer=streamer_,
        flush_timeout=config.default_flush_timeout,
    )


# Opaque, hashable connection identity produced by ``_connection_key``.
ConnectionKey = Tuple[str, bool]


def _connection_key(
    config: opik_config.OpikConfig, *, use_batching: bool
) -> ConnectionKey:
    # The whole config defines a connection's identity: any differing field
    # yields a different bundle. Hashing the serialized config keeps the key
    # compact and, by construction, never holds the api_key (or any field) in
    # plaintext.
    #
    # Note this means clients that differ only by per-handle settings (e.g. a
    # different default ``project_name`` or ``default_flush_timeout``) get
    # separate bundles. That is safe — project is carried per trace and the flush
    # timeout is a per-``end()`` argument — but to share one connection across
    # projects, use a single client and pass ``project_name`` per call.
    fingerprint = json.dumps(config.model_dump(mode="json"), sort_keys=True)
    digest = hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()
    return (digest, use_batching)


class Lease:
    """Per-handle, release-once token over a bundle.

    Each :class:`opik.Opik` handle holds its own lease. It carries the bundle so
    the handle can delegate without re-looking it up, and guards a single
    ``release`` so an explicit ``end()`` followed by the GC finalizer cannot
    release twice. All lifecycle *decisions* (refcount, teardown) live on the
    manager — the lease only forwards.
    """

    def __init__(
        self,
        manager: "ConnectionResourceManager",
        key: ConnectionKey,
        resources: SharedConnectionResourcesBundle,
    ) -> None:
        self._manager = manager
        self._key = key
        self.resources = resources
        self._released = False
        self._once_lock = threading.Lock()

    def release(
        self, timeout: Optional[int], *, flush: bool = True, close_on_zero: bool
    ) -> None:
        with self._once_lock:
            if self._released:
                return
            self._released = True
        self._manager.release(
            self._key, timeout, flush=flush, close_on_zero=close_on_zero
        )


class _Entry:
    def __init__(
        self, resources: SharedConnectionResourcesBundle, refcount: int
    ) -> None:
        self.resources = resources
        self.refcount = refcount


class ConnectionResourceManager:
    """Single owner of the shared connection-resource lifecycle.

    Derives the connection identity from a config, builds-or-reuses a bundle
    ref-counted by that identity, and tears a bundle down only when its last
    lease is released *explicitly* (``Opik.end()``) — always after evicting it
    under the lock, so a concurrent ``acquire`` never receives a closing bundle.
    A reference dropped by a GC finalizer (``close_on_zero=False``) only
    decrements the count and leaves the bundle cached; closing is never done in
    garbage collection. Whatever survives to process exit is disposed by
    ``close_all``. Disposal mechanics are delegated to the bundle's ``close``;
    this class owns *when* it happens.
    """

    def __init__(
        self,
        builder: Callable[
            ..., SharedConnectionResourcesBundle
        ] = create_connection_resources,
    ) -> None:
        self._builder = builder
        self._lock = threading.Lock()
        self._entries: Dict[ConnectionKey, _Entry] = {}

    def acquire(
        self,
        config: opik_config.OpikConfig,
        *,
        use_batching: bool,
    ) -> Lease:
        key = _connection_key(config, use_batching=use_batching)

        # Fast path: an existing bundle is reused under the lock.
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None:
                entry.refcount += 1
                return Lease(manager=self, key=key, resources=entry.resources)

        # No bundle yet — build outside the lock so a slow transport-stack
        # construction does not serialize unrelated acquisitions.
        bundle = self._builder(config, use_batching=use_batching)

        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                self._entries[key] = _Entry(resources=bundle, refcount=1)
                return Lease(manager=self, key=key, resources=bundle)
            # Lost the construction race: keep the bundle that won, take a
            # reference on it, and drop ours below (outside the lock).
            entry.refcount += 1
            lease = Lease(manager=self, key=key, resources=entry.resources)

        # Discard the bundle we lost the race with. A teardown failure here must
        # not reject the caller — the winning lease is already valid — so log and
        # move on.
        try:
            bundle.close(timeout=0, flush=False)
        except Exception:
            LOGGER.debug(
                "Failed to close connection resources discarded after an acquire race",
                exc_info=True,
            )
        return lease

    def release(
        self,
        key: ConnectionKey,
        timeout: Optional[int],
        *,
        flush: bool = True,
        close_on_zero: bool,
    ) -> None:
        # Durability under sharing: an explicit ``end(flush=True)`` on a handle
        # that still shares its bundle must drain the shared queue *before* this
        # handle gives up its reference. Flushing while our reference is still
        # counted keeps refcount >= 1, so a concurrent last-release cannot evict
        # and ``close(flush=False)`` the bundle — which would clear the message
        # queue out from under this flush and lose the data the ``flush=True``
        # caller was promised. Pre-flush only when another handle also shares the
        # bundle; a sole holder's ``close(flush=True)`` below already drains
        # durably. A GC finalizer (``close_on_zero=False``) never does network
        # I/O, so it never pre-flushes.
        if flush and close_on_zero:
            with self._lock:
                entry = self._entries.get(key)
                shared_bundle = (
                    entry.resources
                    if entry is not None and entry.refcount > 1
                    else None
                )
            if shared_bundle is not None:
                shared_bundle.flush(timeout)

        # Now drop our reference. Because we only decrement here — after any
        # pre-flush above has completed — a close can never run while another
        # handle is mid pre-flush: that handle still holds its reference, so the
        # count cannot reach zero until its flush returns.
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return
            entry.refcount -= 1
            if entry.refcount > 0:
                return
            if not close_on_zero:
                # The last reference was dropped by a GC finalizer (see
                # ``Opik._acquire_shared_resources``). Only the refcount
                # decrement above is safe to run there; closing — streamer
                # thread joins, file-upload pool shutdown, network flush — must
                # never happen inside garbage collection. Leave the bundle
                # cached so a later same-identity ``acquire`` reuses it, or
                # ``close_all`` disposes it at process exit.
                return
            # Evict before close, under the lock, so a concurrent acquire never
            # receives a bundle that is being torn down.
            del self._entries[key]
            bundle = entry.resources

        bundle.close(timeout, flush=flush)

    def close_all(self, *, flush: bool = True) -> None:
        """Close and evict every cached bundle. Registered as the process
        ``atexit`` hook (``flush=True``), where each bundle is drained within its
        own connection's configured ``flush_timeout`` rather than unbounded;
        ``flush=False`` resets the registry without network I/O."""
        with self._lock:
            entries = list(self._entries.values())
            self._entries.clear()

        for entry in entries:
            try:
                entry.resources.close(entry.resources.flush_timeout, flush=flush)
            except Exception:
                LOGGER.debug(
                    "Failed to close shared connection resources",
                    exc_info=True,
                )

    def active_connection_count(self) -> int:
        """Number of live cached bundles. For tests and debugging."""
        with self._lock:
            return len(self._entries)

    def reference_count(
        self, config: opik_config.OpikConfig, *, use_batching: bool
    ) -> int:
        """Number of handles currently sharing ``config``'s bundle (0 if none)."""
        key = _connection_key(config, use_batching=use_batching)
        with self._lock:
            entry = self._entries.get(key)
            return 0 if entry is None else entry.refcount


MANAGER = ConnectionResourceManager()
atexit.register(MANAGER.close_all)
