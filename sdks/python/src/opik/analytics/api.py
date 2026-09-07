import atexit
import logging
import os
import re
import sys
import threading
import types
from typing import Any, Callable, Literal, Optional, Set, Tuple, TypeVar

from . import comet_stats, rules, worker
from .worker import PropertyValue
from .. import config

LOGGER = logging.getLogger(__name__)

_F = TypeVar("_F", bound=Callable[..., Any])

_SDK_MODULE_PREFIXES = ("opik.", "_opik")

Component = Literal["client", "configuration", "evaluation", "integration"]
"""
The root of an event's path - which part of the SDK it came from.

A closed set on purpose: it keeps the event namespace small enough to browse in
Segment, and keeps two call sites from reporting the same thing under names that
only a human can tell apart. Deeper levels of the path are free-form; this one is
not. Add a value here rather than passing a new string.

- `client`: `Opik` methods - `create_dataset()`, `search_traces()`, ...
- `configuration`: `opik configure` and `opik mcp configure` - the onboarding
  flow, including which deployment, which AI clients, and where it stops
- `evaluation`: `evaluate()`, `run_tests()`, and which metrics get instantiated
- `integration`: the `track_<library>()` entry point of each integration
"""

MAX_QUEUE_SIZE = 1000
MAX_BATCH_SIZE = 25
BATCH_TIMEOUT_SECONDS = 5.0
DEFAULT_FLUSH_TIMEOUT_SECONDS = 5.0

# Deliberately short: this runs on the way out of the user's process, so an
# unreachable Segment must not become a noticeable hang on exit. The worker is a
# daemon thread, so anything still in flight dies with the process.
ATEXIT_FLUSH_TIMEOUT_SECONDS = 2.0

# How long `shutdown` waits for `_LOCK` before giving up on it, see there.
SHUTDOWN_LOCK_TIMEOUT_SECONDS = 2.0

_EVENT_NAME_PREFIX = "opik_python_sdk"

# Separates the levels of the path. Deliberately doubled: segments are method names, so
# they contain single underscores but never a pair, which makes the joined name
# splittable back into the path it was built from.
_LEVEL_SEPARATOR = "__"

_LOCK = threading.Lock()
_WORKER: Optional[worker.Worker] = None
_SENDER: Optional[comet_stats.Sender] = None
_DISABLED = False

# Which events were already reported, see `track_event`. Guarded by its own lock so
# that claiming an event is atomic - `_LOCK` is held while the worker starts, and
# nesting the two would mean holding both at once.
_REPORTED_LOCK = threading.Lock()
_ALREADY_REPORTED: Set[Tuple[Any, ...]] = set()

# Every function seen reporting an event, by code object. Used to recognise one
# reported call happening inside another - see `_reported_from_inside_the_sdk`.
_REPORTING_CODE: Set[types.CodeType] = set()

# Functions marked with `@internal`. Anything reported beneath one of these is
# Opik acting on its own behalf, see `internal`.
_INTERNAL_CODE: Set[types.CodeType] = set()


def _build_event_name(component: Component, path: Tuple[str, ...]) -> str:
    """
    Composes the name an event is sent under, by joining its path:
    `("integration", "bedrock", "invoke_agent")` becomes
    `opik_python_sdk__integration__bedrock__invoke_agent`.

    Splitting that on `__` gives the path back, so the hierarchy survives into
    whatever reads the events. The scheme lives here alone, so it can be changed for
    every event at once without touching a single call site.

    Runs of underscores inside a segment are collapsed first. Every call site passes
    a literal today, and a test keeps those separator-free, but a segment built at
    runtime would otherwise split into levels that were never meant to exist.
    """
    segments = (
        _EVENT_NAME_PREFIX,
        component,
        *(_collapse_underscores(p) for p in path),
    )
    return _LEVEL_SEPARATOR.join(segments)


def _collapse_underscores(segment: str) -> str:
    return re.sub(r"_{2,}", "_", segment)


def internal(func: _F) -> _F:
    """
    Marks a function whose callees are Opik using itself, not the user using Opik.

    Needed where the module test in `_reported_from_inside_the_sdk` cannot help: an
    internal caller that happens to live in the same module as the thing it calls
    looks exactly like a user calling it. `get_global_client` building an `Opik` is
    that case - both are in `opik_client`, so without this a bare `@track` function
    reports `client__init` and the event counts every user of the SDK rather than
    the ones who built a client.
    """
    _INTERNAL_CODE.add(func.__code__)
    return func


def _is_sdk_module(module: str) -> bool:
    return module == "opik" or module.startswith(_SDK_MODULE_PREFIXES)


def _reported_from_inside_the_sdk() -> bool:
    """
    True when the function reporting this event was reached from another part of
    Opik rather than from the user's code.

    Half of the instrumented `Opik` methods are also used internally - `evaluate_threads`
    calls `search_threads`, `get_or_create_dataset` calls `get_dataset`, the CLI calls
    both. Counting those would describe Opik's own behaviour rather than anyone's usage,
    and since an event is only reported once per process, an internal call arriving
    first would suppress the user's real one.

    Two things make a call internal, and it takes both to cover the cases:

    - Opik reached in from *another of its modules*. This catches internal calls with
      no reported call above them at all, such as the message processor updating a
      span. Arriving from the reporter's own module does not count: that is a private
      helper reporting on its caller's behalf, as `BaseMetric.__init__` does.
    - Some function further up the stack is already reporting, or is marked
      `@internal`. This catches the rest, including one `Opik` method calling another
      on `self` - same module, so the test above cannot see it - and calls that reach
      across several modules on the way.
    """
    try:
        # 0 is this function, 1 is `track_event`, 2 is whatever reported.
        reporter = sys._getframe(2)
    except ValueError:
        return False

    # Registered whether or not this particular event is reported, so that a call
    # nested inside this one recognises it either way.
    _REPORTING_CODE.add(reporter.f_code)

    caller = reporter.f_back
    if caller is None:
        return False

    caller_module = caller.f_globals.get("__name__", "")
    if _is_sdk_module(caller_module) and caller_module != reporter.f_globals.get(
        "__name__", ""
    ):
        return True

    frame: Optional[types.FrameType] = caller
    while frame is not None:
        if frame.f_code in _REPORTING_CODE or frame.f_code in _INTERNAL_CODE:
            return True
        frame = frame.f_back

    return False


def _reset_after_fork() -> None:
    """
    Threads do not survive `fork()`, so a forked child inherits a `_WORKER` whose
    thread is dead and whose queue holds the parent's pending events. Left alone the
    child reports nothing at all, which quietly loses every event from prefork
    servers and `multiprocessing` pools.

    Dropping the worker makes the next event start a fresh one, with a fresh queue -
    so the parent's pending events are not sent twice. `_ALREADY_REPORTED` is
    deliberately kept: the child inherits what the parent has already reported and
    must not report it again. The lock is replaced because a fork can happen while
    another thread holds it, which would leave it locked forever in the child.

    A child picks up its own `pid` and `session_id` without anything being done here:
    they come from `environment_details`, which clears its own cache on its own fork
    hook, for error reports as well.
    """
    global _WORKER, _SENDER, _LOCK, _REPORTED_LOCK

    _WORKER = None
    # Dropped rather than closed: the socket underneath belongs to the parent, and
    # the child builds its own on the next event.
    _SENDER = None
    _LOCK = threading.Lock()
    _REPORTED_LOCK = threading.Lock()


if hasattr(os, "register_at_fork"):
    os.register_at_fork(after_in_child=_reset_after_fork)


def reporting_allowed() -> bool:
    """
    Whether an event reported from this process would be sent anywhere.

    For a call site that has to do work to enrich an event - a lookup, a round-trip
    - and must not do that work when nothing will be reported. Asking here keeps
    `OPIK_ANALYTICS_ENABLE` the single switch that governs analytics, the work done
    to produce it included.

    Every reason `_start_worker` refuses to report has to be a reason here too, or
    an enrichment pays for an event that is then dropped - which is why a missing
    destination counts, not just the opt-out.
    """
    if _DISABLED:
        return False

    try:
        config_ = config.OpikConfig()
        return rules.reporting_allowed(config_) and bool(config_.analytics_url)
    except Exception:
        LOGGER.debug("Failed to decide whether analytics may report", exc_info=True)
        return False


def _disable_after_rejection() -> None:
    """
    Called from the worker thread when the destination has told us to stop. Turning
    `_DISABLED` on makes `track_event` return on its first line, so the rest of the
    process costs nothing rather than queueing events nobody will accept.
    """
    global _DISABLED

    _DISABLED = True
    _close_sender()


def _close_sender() -> None:
    """
    Releases the connection pool. Nothing reopens it: both callers have switched
    reporting off for good, so the sender is finished with either way.
    """
    global _SENDER

    sender, _SENDER = _SENDER, None
    if sender is not None:
        try:
            sender.close()
        except Exception:
            LOGGER.debug("Failed to close the analytics connection", exc_info=True)


def _start_worker() -> Optional[worker.Worker]:
    global _WORKER, _SENDER, _DISABLED

    if _DISABLED:
        return None

    if _WORKER is not None:
        return _WORKER

    with _LOCK:
        if _DISABLED:
            return None
        if _WORKER is not None:
            return _WORKER

        # Read on the first tracked event rather than at import time, so that
        # `opik.configure(...)` and env vars set after the import are picked up.
        config_ = config.OpikConfig()

        if not rules.reporting_allowed(config_):
            _DISABLED = True
            return None

        # Not a second opt-out - `OPIK_ANALYTICS_ENABLE` is that. Without a
        # destination every batch would fail and be swallowed, so there is no point
        # starting a thread to produce them.
        if not config_.analytics_url:
            LOGGER.debug("No analytics URL configured, not reporting")
            _DISABLED = True
            return None

        sender = comet_stats.Sender(url=config_.analytics_url)

        # Last check, and it has to be here rather than only at the top: evaluating
        # the rules above runs arbitrary user code, and `shutdown` gives up on
        # `_LOCK` after two seconds (see there), so reporting can have been switched
        # off for good while this was still deciding. Publishing a worker now would
        # undo that shutdown and leave a thread and a connection pool behind it.
        if _DISABLED:
            sender.close()
            return None

        _SENDER = sender
        _WORKER = worker.Worker(
            send=sender.send,
            max_queue_size=MAX_QUEUE_SIZE,
            max_batch_size=MAX_BATCH_SIZE,
            batch_timeout_seconds=BATCH_TIMEOUT_SECONDS,
            on_rejected=_disable_after_rejection,
        )
        _WORKER.start()
        atexit.register(shutdown, ATEXIT_FLUSH_TIMEOUT_SECONDS)

        return _WORKER


def track_event(
    component: Component, action: str, *sub_actions: str, **properties: PropertyValue
) -> None:
    """
    Reports that an SDK feature was used. A one-liner, safe to call from anywhere:
    it never raises, never blocks on I/O, and does nothing when analytics is
    switched off - so it needs no guarding at the call site.

    The positional arguments form a path, from the broadest grouping to the most
    specific, and can go as deep as an event needs. Reporting a feature and then a
    part of it is just a longer path, which keeps related events sorted next to
    each other wherever they are read.

    The same event is reported **once per process**. Analytics answers "how many
    users use this feature", not "how often", so counting repeats would only add
    load. Events differing in their properties count as different events, so one
    path can still cover variants (each metric class, say).

    Args:
        component: The root of the path - which part of the SDK this is, see
            `Component`. Closed vocabulary, so the namespace cannot sprawl.
        action: What happened, usually the name of the method being reported.
        sub_actions: Optional further levels, narrowing the action down.
        properties: Scalars adding detail - counts, flags, library names. Whatever
            is passed here is what gets sent, so pass no user data: no prompts, no
            payloads, no dataset contents, no names the user chose.

    Example:
        >>> analytics.track_event("client", "create_dataset")
        >>> analytics.track_event("integration", "bedrock", "invoke_agent")
        >>> analytics.track_event("evaluation", "metric_created", metric="Equals")
    """
    # First thing, before even composing the name: once anything has switched
    # reporting off, this is the only line every call site pays for.
    if _DISABLED:
        return

    # Inside the try, not before it: composing the name is not obviously fallible,
    # but a call site passing something that is not a string makes it so, and this
    # runs inside the user-facing methods it reports on. Reporting must never be
    # what breaks one of them.
    name = "<unnamed>"

    try:
        path = (action, *sub_actions)
        name = _build_event_name(component, path)

        already_reported = (name, *sorted(properties.items()))

        # Lock-free fast path. It is allowed to let several threads through on the
        # first call; the claim below is what actually decides.
        if already_reported in _ALREADY_REPORTED:
            return

        # Deliberately after the check above and before the one below: reading the
        # stack is the only costly thing here, so it must not run on the repeat calls
        # that dominate, and an internal call must record nothing - otherwise it would
        # suppress the user's own call to the same API later on.
        if _reported_from_inside_the_sdk():
            return

        worker_ = _start_worker()
        if worker_ is None:
            return

        # Claiming the event and acting on it has to be one step. Checking and then
        # adding lets every thread that raced to the first call report it, so a
        # threaded application would send one copy per thread instead of one in total.
        with _REPORTED_LOCK:
            if already_reported in _ALREADY_REPORTED:
                return
            _ALREADY_REPORTED.add(already_reported)

        if not worker_.enqueue(worker.Event(name=name, properties=dict(properties))):
            # The queue was full, so nothing was recorded. Releasing the claim lets a
            # later call report it rather than the event being lost for the process.
            with _REPORTED_LOCK:
                _ALREADY_REPORTED.discard(already_reported)
    except Exception:
        LOGGER.debug("Failed to track analytics event %s", name, exc_info=True)


def flush(timeout: Optional[float] = DEFAULT_FLUSH_TIMEOUT_SECONDS) -> bool:
    """
    Waits for already-reported events to be sent. False on timeout. Does not start
    the reporting machinery if nothing has been tracked yet.
    """
    try:
        return _WORKER.flush(timeout) if _WORKER is not None else True
    except Exception:
        LOGGER.debug("Failed to flush analytics events", exc_info=True)
        return False


def shutdown(timeout: Optional[float] = DEFAULT_FLUSH_TIMEOUT_SECONDS) -> None:
    """Flushes and stops reporting. Terminal: analytics stays off afterwards."""
    global _WORKER, _DISABLED

    try:
        # Bounded rather than a plain `with`: this runs from `atexit`, and `_LOCK` is
        # held while the worker starts - which evaluates rules, and a rule is
        # arbitrary user code. One that blocks would otherwise hang the interpreter
        # on the way out. Giving up on the lock still switches reporting off; the
        # worker is a daemon thread, so anything left dies with the process.
        acquired = _LOCK.acquire(timeout=SHUTDOWN_LOCK_TIMEOUT_SECONDS)
        _DISABLED = True
        if acquired:
            worker_, _WORKER = _WORKER, None
            _LOCK.release()
        else:
            LOGGER.debug("Analytics lock busy at shutdown, stopping without it")
            worker_ = _WORKER

        if worker_ is not None:
            worker_.close(timeout)

        _close_sender()
    except Exception:
        LOGGER.debug("Failed to shut analytics down", exc_info=True)
