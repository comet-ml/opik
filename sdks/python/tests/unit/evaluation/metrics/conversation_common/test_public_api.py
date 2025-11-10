import importlib

import pytest


@pytest.mark.parametrize(
    "attribute",
    [
        "ConversationThreadMetric",
        "ConversationDegenerationMetric",
        "KnowledgeRetentionMetric",
        "ConversationalCoherenceMetric",
        "SessionCompletenessQuality",
        "UserFrustrationMetric",
    ],
)
def test_conversation_namespace_exports_public_symbols(attribute: str) -> None:
    module = importlib.import_module("opik.evaluation.metrics.conversation")

    assert hasattr(
        module, attribute
    ), f"{attribute} missing from conversation namespace"
    assert getattr(module, attribute) is not None
