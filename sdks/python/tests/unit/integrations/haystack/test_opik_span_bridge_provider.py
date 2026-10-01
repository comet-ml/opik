"""The provider recorded for a HayStack component must match the component.

`OpikSpanBridge` names a span after the user's component (`haystack.component.name`)
and then fills in provider/model/usage. The provider is therefore the only field
that tells an Azure OpenAI deployment apart from a public-OpenAI one -- the
component name is the user's own, and the model is often the same string.

Both Azure component names embed "OpenAI", so the mapping has to test "Azure"
first; testing it second made that branch unreachable.
"""

import pytest

# `opik.integrations.haystack.opik_span_bridge` imports haystack at module
# level, so the skip has to come before those imports: importing first turns a
# missing optional dependency into a collection error instead of a skip.
haystack = pytest.importorskip("haystack")

from opik.integrations.haystack import constants  # noqa: E402
from opik.integrations.haystack.opik_span_bridge import OpikSpanBridge  # noqa: E402

AZURE_COMPONENTS = [
    component
    for component in constants.ALL_SUPPORTED_GENERATORS
    if "Azure" in component
]

# Every component the integration advertises, so a newly supported component
# fails here until its provider is spelled out.
EXPECTED_PROVIDERS = {
    "AzureOpenAIGenerator": "azure",
    "AzureOpenAIChatGenerator": "azure",
    "OpenAIGenerator": "openai",
    "OpenAIChatGenerator": "openai",
    "AnthropicGenerator": "anthropic",
    "AnthropicChatGenerator": "anthropic",
    "HuggingFaceAPIGenerator": "huggingface",
    "HuggingFaceAPIChatGenerator": "huggingface",
    "HuggingFaceLocalGenerator": "huggingface",
    "HuggingFaceLocalChatGenerator": "huggingface",
    "CohereGenerator": "cohere",
    "CohereChatGenerator": "cohere",
}


@pytest.fixture
def bridge() -> OpikSpanBridge:
    # The method is pure -- it reads only its argument -- so no wiring needed.
    return OpikSpanBridge.__new__(OpikSpanBridge)


def test_expected_providers_cover_every_supported_component():
    # Without this, a component added to the integration would silently go
    # untested rather than failing.
    assert sorted(EXPECTED_PROVIDERS) == sorted(constants.ALL_SUPPORTED_GENERATORS)


def test_supported_components_contain_the_azure_ones():
    # If the supported list ever stops naming them, this test would pass for the
    # wrong reason, so assert the premise.
    assert sorted(AZURE_COMPONENTS) == [
        "AzureOpenAIChatGenerator",
        "AzureOpenAIGenerator",
    ]
    for component in AZURE_COMPONENTS:
        assert "OpenAI" in component, (
            f"{component} is the reason the mapping has to test Azure first"
        )


@pytest.mark.parametrize("component_type,expected", sorted(EXPECTED_PROVIDERS.items()))
def test_extract_provider_from_component_type(
    bridge: OpikSpanBridge, component_type: str, expected: str
):
    assert bridge._extract_provider_from_component_type(component_type) == expected


def test_azure_and_openai_component_types_extract_different_providers(
    bridge: OpikSpanBridge,
):
    # The user-visible consequence: two spans for the same model, one through
    # Azure and one through OpenAI, were indistinguishable by provider.
    azure = bridge._extract_provider_from_component_type("AzureOpenAIChatGenerator")
    openai = bridge._extract_provider_from_component_type("OpenAIChatGenerator")

    assert azure != openai
    assert azure == "azure"


def test_unknown_component_still_falls_back(bridge: OpikSpanBridge):
    # The reorder must not disturb the suffix-stripping fallback.
    assert bridge._extract_provider_from_component_type("CustomGenerator") == "custom"
