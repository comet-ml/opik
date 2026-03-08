from __future__ import annotations

from typing import Any, Optional, TypeVar

import opik

ExaClient = TypeVar("ExaClient", bound=object)

_EXA_SEARCH_METHODS = (
    "search",
    "search_and_contents",
    "searchAndContents",
    "find_similar",
    "findSimilar",
    "answer",
    "get_contents",
    "getContents",
)


def _build_metadata(operation: str) -> dict[str, Any]:
    return {
        "created_from": "exa",
        "opik.kind": "search",
        "opik.provider": "exa",
        "opik.operation": operation,
    }


def _patch_method(
    exa_client: ExaClient,
    method_name: str,
    project_name: Optional[str],
) -> None:
    method = getattr(exa_client, method_name, None)
    if not callable(method):
        return

    decorator = opik.track(
        type="tool",
        name=f"exa.{method_name}",
        metadata=_build_metadata(method_name),
        project_name=project_name,
    )
    setattr(exa_client, method_name, decorator(method))


def track_exa(
    exa_client: ExaClient,
    project_name: Optional[str] = None,
) -> ExaClient:
    """Adds Opik tracking wrappers to an Exa client.

    Supported methods (patched when available):
    * `search()`
    * `search_and_contents()`
    * `find_similar()`
    * `answer()`

    Each patched call emits a `tool` span with metadata:
    * `opik.kind=search`
    * `opik.provider=exa`
    * `opik.operation=<method_name>`
    """
    if hasattr(exa_client, "opik_tracked"):
        return exa_client

    setattr(exa_client, "opik_tracked", True)

    for method_name in _EXA_SEARCH_METHODS:
        _patch_method(exa_client, method_name, project_name)

    return exa_client
