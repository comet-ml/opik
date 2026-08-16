"""Usage extraction must survive absent model metadata."""

from opik.integrations.langchain.provider_usage_extractors import usage_extractor


def _openai_streaming_run_without_generation_info() -> dict:
    """Streaming-shape OpenAI run: usage in message usage_metadata, no generation_info."""
    return {
        "serialized": {"kwargs": {"openai_api_key": "sk-test"}},
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


def _openai_run_without_extra() -> dict:
    """Invoke-shape OpenAI run with usage but no 'extra' key at all."""
    return {
        "serialized": {"kwargs": {"openai_api_key": "sk-test"}},
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


def test_openai_usage_survives_missing_generation_info():
    info = usage_extractor.try_extract_provider_usage_data(
        _openai_streaming_run_without_generation_info()
    )
    assert info is not None, (
        "usage must be extracted even when generation_info is absent"
    )
    assert info.usage is not None
    assert info.usage.prompt_tokens == 10
    assert info.model is None


def test_openai_usage_survives_missing_extra():
    info = usage_extractor.try_extract_provider_usage_data(_openai_run_without_extra())
    assert info is not None, (
        "usage must be extracted even when the run has no 'extra' key"
    )
    assert info.usage is not None
    assert info.usage.prompt_tokens == 10
    assert info.model is None
