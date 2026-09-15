"""Usage extraction must survive absent model metadata."""

from typing import Any, Dict

import pytest

from opik.integrations.langchain.provider_usage_extractors import usage_extractor


def _openai_streaming_run_without_generation_info() -> Dict[str, Any]:
    """Streaming-shape OpenAI run: usage in message usage_metadata, no generation_info."""
    return {
        "serialized": {"kwargs": {"openai_api_key": "fixture-value"}},
        "extra": {},
        "outputs": {
            "llm_output": None,
            "generations": [
                [
                    {
                        "message": {
                            "kwargs": {},
                            "usage_metadata": {
                                "input_tokens": 10,
                                "output_tokens": 20,
                                "total_tokens": 30,
                            },
                        },
                    }
                ]
            ],
        },
    }


def _openai_run_without_extra() -> Dict[str, Any]:
    """Invoke-shape OpenAI run with usage but no 'extra' key at all."""
    return {
        "serialized": {"kwargs": {"openai_api_key": "fixture-value"}},
        "outputs": {
            "llm_output": {
                "token_usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                }
            }
        },
    }


def _openai_run_with_null_metadata() -> Dict[str, Any]:
    """OpenAI run where extra.metadata and extra.invocation_params are explicit None."""
    return {
        "serialized": {"kwargs": {"openai_api_key": "fixture-value"}},
        "extra": {"metadata": None, "invocation_params": None},
        "outputs": {
            "llm_output": {
                "token_usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                }
            }
        },
    }


@pytest.mark.parametrize(
    "run_dict",
    [
        pytest.param(
            _openai_streaming_run_without_generation_info(),
            id="missing_generation_info",
        ),
        pytest.param(_openai_run_without_extra(), id="missing_extra"),
        pytest.param(_openai_run_with_null_metadata(), id="explicit_null_metadata"),
    ],
)
def test_try_extract_provider_usage_data__missing_model_metadata__returns_usage_with_null_model(
    run_dict: Dict[str, Any],
) -> None:
    info = usage_extractor.try_extract_provider_usage_data(run_dict)
    assert info is not None, (
        "usage must be extracted even when model metadata is absent"
    )
    assert info.usage is not None
    assert info.usage.prompt_tokens == 10
    assert info.model is None


def _anthropic_run_with_empty_generations() -> Dict[str, Any]:
    """Anthropic run where generations list is empty — must not raise IndexError."""
    return {
        "serialized": {"kwargs": {"anthropic_api_key": "fixture-value"}},
        "extra": {},
        "outputs": {"llm_output": None, "generations": []},
    }


def _anthropic_run_with_null_outputs() -> Dict[str, Any]:
    """Anthropic run where outputs is an unexpected type — must not raise TypeError."""
    return {
        "serialized": {"kwargs": {"anthropic_api_key": "fixture-value"}},
        "extra": {},
        "outputs": None,
    }


def test_try_get_streaming_token_usage__empty_generations__returns_none() -> None:
    from opik.integrations.langchain.provider_usage_extractors.langchain_run_helpers import (
        helpers,
    )

    run_dict = _anthropic_run_with_empty_generations()
    result = helpers.try_get_streaming_token_usage(run_dict)
    assert result is None, "empty generations must return None, not raise IndexError"


def test_try_get_streaming_token_usage__null_outputs__returns_none() -> None:
    from opik.integrations.langchain.provider_usage_extractors.langchain_run_helpers import (
        helpers,
    )

    run_dict = _anthropic_run_with_null_outputs()
    result = helpers.try_get_streaming_token_usage(run_dict)
    assert result is None, "null outputs must return None, not raise TypeError"


def test_try_get_streaming_token_usage__usage_metadata_on_message__returns_usage() -> (
    None
):
    """usage_metadata lives directly on the message dict, not inside message.kwargs."""
    from opik.integrations.langchain.provider_usage_extractors.langchain_run_helpers import (
        helpers,
        langchain_usage,
    )

    run_dict = {
        "outputs": {
            "generations": [
                [
                    {
                        "message": {
                            "kwargs": {},
                            "usage_metadata": {
                                "input_tokens": 5,
                                "output_tokens": 8,
                                "total_tokens": 13,
                            },
                        }
                    }
                ]
            ]
        }
    }
    result = helpers.try_get_streaming_token_usage(run_dict)
    assert isinstance(result, langchain_usage.LangChainUsage)
    assert result.input_tokens == 5
    assert result.output_tokens == 8


def _anthropic_vertexai_run_with_usage() -> Dict[str, Any]:
    """Valid Anthropic VertexAI invocation shape with streaming usage_metadata."""
    return {
        "serialized": {},
        "extra": {
            "invocation_params": {
                "_type": "ChatAnthropicVertexAI",
                "model_name": "claude-3-5-sonnet@20241022",
            }
        },
        "outputs": {
            "llm_output": None,
            "generations": [
                [
                    {
                        "message": {
                            "usage_metadata": {
                                "input_tokens": 15,
                                "output_tokens": 25,
                                "total_tokens": 40,
                            }
                        }
                    }
                ]
            ],
        },
    }


def test_try_extract_provider_usage_data__anthropic_vertexai__returns_usage_and_model() -> None:
    """Full public-API path: AnthropicVertexAIUsageExtractor reached, usage and model extracted."""
    import opik

    run_dict = _anthropic_vertexai_run_with_usage()
    info = usage_extractor.try_extract_provider_usage_data(run_dict)

    assert info is not None, "Anthropic VertexAI run must yield usage info"
    assert info.provider == opik.LLMProvider.ANTHROPIC_VERTEXAI
    assert info.model == "claude-3-5-sonnet@20241022"
    assert info.usage is not None
    assert info.usage.prompt_tokens == 15
    assert info.usage.completion_tokens == 25
