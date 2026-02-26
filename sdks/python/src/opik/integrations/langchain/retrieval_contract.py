from typing import Any, Dict, Mapping, Optional

OPIK_KIND_KEY = "opik.kind"
OPIK_PROVIDER_KEY = "opik.provider"
OPIK_OPERATION_KEY = "opik.operation"

RETRIEVAL_KIND = "retrieval"
DEFAULT_RETRIEVAL_OPERATION = "retrieve"

_GENERIC_RETRIEVER_IDS = {
    "retriever",
    "base",
    "vectorstore",
    "vector_store",
    "langchain",
    "langchain_core",
    "langchain_community",
}


def _normalize_provider(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None

    normalized = value.strip().lower().replace(" ", "_")
    if not normalized or normalized in _GENERIC_RETRIEVER_IDS:
        return None

    return normalized


def _infer_provider_from_serialized(run_dict: Dict[str, Any]) -> Optional[str]:
    serialized = run_dict.get("serialized")
    if not isinstance(serialized, Mapping):
        return None

    identifiers = serialized.get("id")
    if not isinstance(identifiers, list):
        return None

    for identifier in reversed(identifiers):
        normalized = _normalize_provider(identifier)
        if normalized:
            return normalized

    return None


def build_retrieval_metadata(
    run_dict: Dict[str, Any], base_metadata: Dict[str, Any]
) -> Dict[str, Any]:
    metadata = dict(base_metadata)

    metadata.setdefault(OPIK_KIND_KEY, RETRIEVAL_KIND)
    metadata.setdefault(OPIK_OPERATION_KEY, DEFAULT_RETRIEVAL_OPERATION)

    provider = _normalize_provider(metadata.get("ls_provider"))
    if provider is None:
        provider = _infer_provider_from_serialized(run_dict)

    if provider is not None:
        metadata.setdefault(OPIK_PROVIDER_KEY, provider)

    return metadata
