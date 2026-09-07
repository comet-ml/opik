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
