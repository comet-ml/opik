from __future__ import annotations

from opik.integrations.langchain import retrieval_contract


def test_build_retrieval_metadata__adds_required_contract_keys() -> None:
    run_dict = {"serialized": {"id": ["langchain_community", "qdrant", "retriever"]}}
    metadata = retrieval_contract.build_retrieval_metadata(run_dict, {})

    assert metadata["opik.kind"] == "retrieval"
    assert metadata["opik.operation"] == "retrieve"
    assert metadata["opik.provider"] == "qdrant"


def test_build_retrieval_metadata__does_not_override_existing_keys() -> None:
    run_dict = {"serialized": {"id": ["langchain_community", "pinecone", "retriever"]}}
    existing = {
        "opik.kind": "search",
        "opik.operation": "query",
        "opik.provider": "custom",
    }
    metadata = retrieval_contract.build_retrieval_metadata(run_dict, existing)

    assert metadata["opik.kind"] == "search"
    assert metadata["opik.operation"] == "query"
    assert metadata["opik.provider"] == "custom"
