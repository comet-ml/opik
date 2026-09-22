import importlib.metadata
from typing import Optional, TypeVar, Union

from typesafe_sdk import AsyncTypeSafeClient, TypeSafeClient

import opik.semantic_version as semantic_version
from opik.types import LLMProvider

from . import typesafe_decorator
from ... import analytics

# 0.7.0 is the first typesafe-sdk release whose response objects are pydantic
# models (`model_dump`); earlier releases returned msgspec structs.
MINIMUM_TYPESAFE_SDK_VERSION = "0.7.0"

TypeSafeClientT = TypeVar("TypeSafeClientT", TypeSafeClient, AsyncTypeSafeClient)


def _assert_supported_typesafe_sdk_version() -> None:
    try:
        installed_version = importlib.metadata.version("typesafe-sdk")
    except importlib.metadata.PackageNotFoundError:
        return

    if (
        semantic_version.SemanticVersion.parse(installed_version)
        < MINIMUM_TYPESAFE_SDK_VERSION
    ):
        raise RuntimeError(
            f"Opik supports typesafe-sdk>={MINIMUM_TYPESAFE_SDK_VERSION}, but version "
            f"{installed_version} is installed. Please upgrade with "
            f'`pip install "typesafe-sdk>={MINIMUM_TYPESAFE_SDK_VERSION},<1"`.'
        )


def track_typesafe(
    typesafe_client: TypeSafeClientT,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> TypeSafeClientT:
    """Adds Opik tracking to a TypeSafe AI client (Jev and other System One models).

    Tracks calls to ``client.system_one()`` on both the sync ``TypeSafeClient``
    and the async ``AsyncTypeSafeClient``. Each call is logged as a single
    ``llm`` span named ``system_one`` with the request ``state`` and
    ``questions`` as input, the returned ``answers`` as output, and the
    reported token usage.

    The client is always patched; however every wrapped call checks
    ``opik.is_tracing_active()`` before emitting any telemetry. If tracing is
    disabled at call time, the wrapped function executes normally but no
    span/trace is sent.

    Can be used within other Opik-tracked functions.

    Args:
        typesafe_client: An instance of ``typesafe_sdk.TypeSafeClient`` or
            ``typesafe_sdk.AsyncTypeSafeClient``.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "typesafe". Accepts any string or an ``opik.LLMProvider`` enum member.

    Returns:
        The same client instance with Opik tracking enabled.
    """
    analytics.track_event("integration", "typesafe")
    _assert_supported_typesafe_sdk_version()

    if hasattr(typesafe_client, "opik_tracked"):
        return typesafe_client

    typesafe_client.opik_tracked = True

    if provider is None:
        resolved_provider = typesafe_decorator.PROVIDER
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = typesafe_decorator.TypeSafeTrackDecorator()
    decorator_factory.provider = resolved_provider

    system_one_decorator = decorator_factory.track(
        type="llm",
        name="system_one",
        project_name=project_name,
    )

    typesafe_client.system_one = system_one_decorator(typesafe_client.system_one)

    return typesafe_client
