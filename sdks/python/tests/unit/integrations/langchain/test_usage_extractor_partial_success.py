"""Regression for OPIK-7860: LangChain usage extractors discard an already-extracted
usage payload whenever the model-name or provider lookup raises.

The orchestrator in `provider_usage_extractors.usage_extractor.try_extract_provider_usage_data`
catches every exception from `get_llm_usage_info` and returns `None`, so any
unguarded `run_dict["extra"]` / `run_dict["outputs"]` access in the model
extraction helpers would silently drop the usage. The fix hardens each
extractor's `_try_get_model_name` / `_get_provider` to return `None` on a
missing key, and wraps the model extraction call in `get_llm_usage_info` in a
defensive `try/except` so a partial-success payload (usage extracted, model
unresolvable) is preserved end-to-end.

This test verifies the per-extractor behaviour by mocking the token-usage
helper to return a known OpikUsage and then asserting that `get_llm_usage_info`
returns an LLMUsageInfo with the usage preserved and `model=None` when the
run_dict is missing the keys the helper would normally read.

The end-to-end test at the bottom exercises the orchestrator with a
streaming-shape OpenAI run whose generation dict has no `generation_info` key
(the issue's exact repro) and asserts the result is not `None`.
"""

from typing import Any, Dict
from unittest import mock

import pytest

from opik import llm_usage
from opik.integrations.langchain.provider_usage_extractors import (
    anthropic_usage_extractor,
    anthropic_vertexai_usage_extractor,
    google_generative_ai_usage_extractor,
    groq_usage_extractor,
    openai_usage_extractor,
    usage_extractor,
    vertexai_usage_extractor,
)
from opik.llm_usage import opik_usage as opik_usage_module


# A minimal OpikUsage covering all three token counts so we can assert
# the helper preserved the value end-to-end.
@pytest.fixture
def stub_opik_usage() -> llm_usage.OpikUsage:
    return opik_usage_module.OpikUsage(
        completion_tokens=5,
        prompt_tokens=10,
        total_tokens=15,
        provider_usage=opik_usage_module.unknown_usage.UnknownUsage(
            original_usage={"input_tokens": 10, "output_tokens": 5}
        ),
    )


# Provider -> (extractor_instance, module_that_owns_its__try_get_token_usage).
# Each entry is one call site that had to be hardened.
PROVIDER_EXTRACTORS = [
    pytest.param(
        openai_usage_extractor.OpenAIUsageExtractor(),
        openai_usage_extractor,
        id="openai",
    ),
    pytest.param(
        anthropic_usage_extractor.AnthropicUsageExtractor(),
        anthropic_usage_extractor,
        id="anthropic",
    ),
    pytest.param(
        groq_usage_extractor.GroqUsageExtractor(),
        groq_usage_extractor,
        id="groq",
    ),
    pytest.param(
        vertexai_usage_extractor.VertexAIUsageExtractor(),
        vertexai_usage_extractor,
        id="vertexai",
    ),
    pytest.param(
        anthropic_vertexai_usage_extractor.AnthropicVertexAIUsageExtractor(),
        anthropic_vertexai_usage_extractor,
        id="anthropic_vertexai",
    ),
    pytest.param(
        google_generative_ai_usage_extractor.GoogleGenerativeAIUsageExtractor(),
        google_generative_ai_usage_extractor,
        id="google_generative_ai",
    ),
]


@pytest.mark.parametrize("extractor,source_module", PROVIDER_EXTRACTORS)
def test_get_llm_usage_info_preserves_usage_when_model_keys_missing(
    extractor: Any, source_module: Any, stub_opik_usage: llm_usage.OpikUsage
) -> None:
    """A run_dict missing the model-extraction keys must not drop the usage.

    Before the fix, `get_llm_usage_info` accessed `run_dict["extra"]` /
    `run_dict["outputs"]["generations"][-1][-1]["generation_info"]` directly
    and raised into the orchestrator's catch-all, which returned `None` and
    silently dropped the already-extracted usage. After the fix, the
    `_try_get_model_name` helpers return `None` and `get_llm_usage_info` wraps
    the model call in a defensive `try/except` so the partial-success payload
    is preserved.
    """
    # Empty `extra` and empty `outputs` mimic the run_dict shape the issue
    # reported for partial / older langchain runs. Pre-fix this raised
    # KeyError; post-fix the model extraction returns None and the usage
    # is returned untouched.
    run_dict: Dict[str, Any] = {"extra": {}, "outputs": {}}

    with mock.patch.object(
        source_module, "_try_get_token_usage", return_value=stub_opik_usage
    ):
        result = extractor.get_llm_usage_info(run_dict)

    assert result is not None
    assert result.usage is stub_opik_usage, (
        "Usage payload was dropped when the model-extraction keys were "
        "missing from the run_dict."
    )
    assert result.model is None, (
        "Model must be None when the run_dict is missing the keys the "
        "extractor normally reads; the issue's expected behavior is to "
        "keep the usage and report model=None rather than raising."
    )


def test_openai_streaming_without_generation_info_keeps_usage(
    stub_opik_usage: llm_usage.OpikUsage,
) -> None:
    """The issue's exact repro for openai: streaming shape with usage in
    `message.kwargs.usage_metadata` but no `generation_info` on the generation
    dict. Pre-fix, the unguarded `run_dict["outputs"]["generations"][-1][-1][
    "generation_info"]` raised KeyError into the orchestrator and the usage
    was dropped. Post-fix, the helper returns None and the usage is kept.
    """
    run_dict: Dict[str, Any] = {
        "extra": {},
        "outputs": {
            "generations": [
                [
                    {
                        "message": {
                            "kwargs": {
                                "usage_metadata": {
                                    "input_tokens": 10,
                                    "output_tokens": 5,
                                    "total_tokens": 15,
                                }
                            }
                        }
                    }
                ]
            ]
        },
    }

    with mock.patch.object(
        openai_usage_extractor, "_try_get_token_usage", return_value=stub_opik_usage
    ):
        result = openai_usage_extractor.OpenAIUsageExtractor().get_llm_usage_info(
            run_dict
        )

    assert result is not None
    assert result.usage is stub_opik_usage
    assert result.model is None


def test_anthropic_empty_generations_keeps_usage(
    stub_opik_usage: llm_usage.OpikUsage,
) -> None:
    """Anthropic: empty generations list raised `IndexError` (only `KeyError`
    was caught) and dropped the usage. Post-fix the helper returns None.
    """
    run_dict: Dict[str, Any] = {
        "extra": {},
        "outputs": {"generations": []},
    }

    with mock.patch.object(
        anthropic_usage_extractor,
        "_try_get_token_usage",
        return_value=stub_opik_usage,
    ):
        result = anthropic_usage_extractor.AnthropicUsageExtractor().get_llm_usage_info(
            run_dict
        )

    assert result is not None
    assert result.usage is stub_opik_usage
    assert result.model is None


def test_orchestrator_returns_usage_when_openai_extractor_preserves_partial_success(
    stub_opik_usage: llm_usage.OpikUsage,
) -> None:
    """End-to-end: the orchestrator iterates the registered extractors, the
    OpenAI extractor matches the `openai_api_key` in `serialized.kwargs`, and
    the partial-success payload is returned (not None) when the model
    extraction cannot resolve a model name.
    """
    run_dict: Dict[str, Any] = {
        "serialized": {"kwargs": {"openai_api_key": "test-key"}},
        "extra": {},
        "outputs": {
            "generations": [
                [
                    {
                        "message": {
                            "kwargs": {
                                "usage_metadata": {
                                    "input_tokens": 10,
                                    "output_tokens": 5,
                                    "total_tokens": 15,
                                }
                            }
                        }
                    }
                ]
            ]
        },
    }

    with mock.patch.object(
        openai_usage_extractor, "_try_get_token_usage", return_value=stub_opik_usage
    ):
        result = usage_extractor.try_extract_provider_usage_data(run_dict)

    assert result is not None, (
        "Orchestrator returned None despite the OpenAI extractor being able "
        "to resolve a usage payload; the partial-success contract from the "
        "issue is broken."
    )
    assert result.usage is stub_opik_usage
    assert result.model is None
