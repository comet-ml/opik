import pytest
from opik.llm_usage.opik_usage import OpikUsage


class TestOpikUsageMultimodal:
    """Test class for multimodal usage functionality."""

    def test_opik_usage__from_openai_completions_dict__without_audio_tokens(self):
        """Test that missing audio tokens are handled gracefully."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Standard tokens should be present
        assert backend_dict["completion_tokens"] == 100
        assert backend_dict["prompt_tokens"] == 200
        assert backend_dict["total_tokens"] == 300

    def test_opik_usage__from_openai_completions_dict__partial_audio_tokens(self):
        """Test that partial audio token data is handled correctly."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "audio_tokens": 20,
            },
            # No prompt_tokens_details
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Only completion audio tokens should be available
        assert backend_dict["original_usage.completion_tokens_details.audio_tokens"] == 20
        assert "original_usage.prompt_tokens_details.audio_tokens" not in backend_dict

    def test_opik_usage__from_openai_completions_dict__zero_audio_tokens(self):
        """Test that zero audio tokens are handled correctly."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "audio_tokens": 0,
            },
            "prompt_tokens_details": {
                "audio_tokens": 0,
            },
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Zero values should be present as flattened keys
        assert backend_dict["original_usage.completion_tokens_details.audio_tokens"] == 0
        assert backend_dict["original_usage.prompt_tokens_details.audio_tokens"] == 0

    def test_opik_usage__backend_compatible_dict__excludes_none_values(self):
        """Test that None values are excluded from backend compatible dict."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Should only contain integer values, no None values
        for key, value in backend_dict.items():
            assert isinstance(value, int), f"Key {key} has non-integer value: {value}"

    def test_opik_usage__backend_compatible_dict__all_original_usage_keys_prefixed(self):
        """Test that all original usage keys are properly prefixed."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "audio_tokens": 20,
                "reasoning_tokens": 30,
            },
            "prompt_tokens_details": {
                "audio_tokens": 10,
                "cached_tokens": 15,
            },
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Check that original usage keys are prefixed
        original_keys = [key for key in backend_dict.keys() if key.startswith("original_usage.")]
        
        expected_original_keys = [
            "original_usage.completion_tokens",
            "original_usage.prompt_tokens", 
            "original_usage.total_tokens",
            "original_usage.completion_tokens_details.audio_tokens",
            "original_usage.completion_tokens_details.reasoning_tokens",
            "original_usage.prompt_tokens_details.audio_tokens",
            "original_usage.prompt_tokens_details.cached_tokens",
        ]
        
        for expected_key in expected_original_keys:
            assert expected_key in original_keys, f"Missing expected key: {expected_key}"

    def test_opik_usage__backend_compatible_dict__contains_both_openai_format_and_original(self):
        """Test that backend dict contains both OpenAI format keys and original flattened keys."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "audio_tokens": 20,
            },
            "prompt_tokens_details": {
                "audio_tokens": 10,
            },
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # OpenAI format keys (without prefix)
        assert "completion_tokens" in backend_dict
        assert "prompt_tokens" in backend_dict
        assert "total_tokens" in backend_dict
        
        # Original usage keys (with prefix) for detailed price calculation
        assert "original_usage.completion_tokens" in backend_dict
        assert "original_usage.prompt_tokens" in backend_dict
        assert "original_usage.total_tokens" in backend_dict
        assert "original_usage.completion_tokens_details.audio_tokens" in backend_dict
        assert "original_usage.prompt_tokens_details.audio_tokens" in backend_dict
        
        # Values should match
        assert backend_dict["completion_tokens"] == backend_dict["original_usage.completion_tokens"]
        assert backend_dict["prompt_tokens"] == backend_dict["original_usage.prompt_tokens"]
        assert backend_dict["total_tokens"] == backend_dict["original_usage.total_tokens"] 