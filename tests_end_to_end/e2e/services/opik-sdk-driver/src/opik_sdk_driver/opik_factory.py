import os
import threading

import opik

# The env var `OpikConfig.enable_json_request_compression` reads from, spelled
# out rather than derived: the SDK's `env_prefix` is a pydantic-settings detail,
# and a typo here would silently leave compression on rather than fail.
_COMPRESSION_ENV = "OPIK_ENABLE_JSON_REQUEST_COMPRESSION"

# Serialises every client construction in this process. `OpikConfig` reads the
# environment at instantiation, so the only way to build a client with
# compression off is to set the variable around the constructor — and
# `os.environ` is process-global while FastAPI runs sync routes on a thread
# pool. Holding this for the construction of EVERY client (not just an
# overridden one) is what stops a concurrent request picking the override up.
# It covers construction only; the upload itself runs outside it, because the
# setting is baked onto the httpx client the constructor built and is read back
# off that object rather than off the environment.
_CONSTRUCTION_LOCK = threading.Lock()


def make_opik_client(
    *,
    workspace: str | None = None,
    api_key: str | None = None,
    enable_json_request_compression: bool | None = None,
) -> opik.Opik:
    """Construct an opik.Opik() with optional per-request workspace + api_key.

    When either argument is None, falls back to the SDK's env-based defaults
    (OPIK_WORKSPACE, OPIK_API_KEY). Used by every route in this bridge so the
    auth/workspace wiring stays in one place.

    `enable_json_request_compression` toggles whether bodies sent through the
    resulting client are gzipped. `None` leaves the environment alone, so the
    client gets whatever the deployment is configured for — which is the SDK's
    own default of True unless something set otherwise. It is applied through
    the environment because that is the only surface the SDK exposes for it:
    `opik.Opik()` takes no such argument, and reaching into the constructed
    client's transport would be an internals grab the suite forbids. The whole
    config is part of the SDK's connection identity, so a differing value here
    yields a genuinely separate httpx client rather than a cached compressing
    one.
    """
    kwargs: dict[str, str] = {}
    if workspace:
        kwargs["workspace"] = workspace
    if api_key:
        kwargs["api_key"] = api_key

    with _CONSTRUCTION_LOCK:
        if enable_json_request_compression is None:
            return opik.Opik(**kwargs) if kwargs else opik.Opik()

        previous = os.environ.get(_COMPRESSION_ENV)
        os.environ[_COMPRESSION_ENV] = (
            "true" if enable_json_request_compression else "false"
        )
        try:
            return opik.Opik(**kwargs) if kwargs else opik.Opik()
        finally:
            if previous is None:
                del os.environ[_COMPRESSION_ENV]
            else:
                os.environ[_COMPRESSION_ENV] = previous
