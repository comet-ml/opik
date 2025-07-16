import pytest
from opik.llm_usage.opik_usage import OpikUsage


class TestOpikUsageMultimodal:
    """Test class for multimodal usage functionality."""

    def test_opik_usage__from_openai_completions_dict__with_multimodal_fields(self):
        """Test that new multimodal fields (image_count, audio_seconds, video_seconds) are handled correctly."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "reasoning_tokens": 30,
            },
            "prompt_tokens_details": {
                "cached_tokens": 15,
            },
            "image_count": 2,
            "audio_seconds": 45,
            "video_seconds": 120,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Multimodal fields should be present in backend dict
        assert backend_dict["original_usage.image_count"] == 2
        assert backend_dict["original_usage.audio_seconds"] == 45
        assert backend_dict["original_usage.video_seconds"] == 120

    def test_opik_usage__from_openai_completions_dict__partial_multimodal_fields(self):
        """Test that partial multimodal field data is handled correctly."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "image_count": 3,
            # No audio_seconds or video_seconds
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Only image_count should be available
        assert backend_dict["original_usage.image_count"] == 3
        assert "original_usage.audio_seconds" not in backend_dict
        assert "original_usage.video_seconds" not in backend_dict

    def test_opik_usage__from_openai_completions_dict__zero_multimodal_fields(self):
        """Test that zero multimodal field values are handled correctly."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "image_count": 0,
            "audio_seconds": 0,
            "video_seconds": 0,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Zero values should be present as flattened keys
        assert backend_dict["original_usage.image_count"] == 0
        assert backend_dict["original_usage.audio_seconds"] == 0
        assert backend_dict["original_usage.video_seconds"] == 0

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

    def test_opik_usage__backend_compatible_dict__all_original_usage_keys_prefixed_with_multimodal(self):
        """Test that all original usage keys including multimodal fields are properly prefixed."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "reasoning_tokens": 30,
            },
            "prompt_tokens_details": {
                "cached_tokens": 15,
            },
            "image_count": 5,
            "audio_seconds": 60,
            "video_seconds": 180,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        backend_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Check that original usage keys are prefixed
        original_keys = [key for key in backend_dict.keys() if key.startswith("original_usage.")]
        
        expected_original_keys = [
            "original_usage.completion_tokens",
            "original_usage.prompt_tokens", 
            "original_usage.total_tokens",
            "original_usage.completion_tokens_details.reasoning_tokens",
            "original_usage.prompt_tokens_details.cached_tokens",
            "original_usage.image_count",
            "original_usage.audio_seconds",
            "original_usage.video_seconds",
        ]
        
        for expected_key in expected_original_keys:
            assert expected_key in original_keys, f"Missing expected key: {expected_key}"

    def test_opik_usage__backend_compatible_dict__contains_both_openai_format_and_original_with_multimodal(self):
        """Test that backend dict contains both OpenAI format keys and original flattened keys including multimodal fields."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "reasoning_tokens": 20,
            },
            "prompt_tokens_details": {
                "cached_tokens": 10,
            },
            "image_count": 3,
            "audio_seconds": 30,
            "video_seconds": 90,
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
        assert "original_usage.completion_tokens_details.reasoning_tokens" in backend_dict
        assert "original_usage.prompt_tokens_details.cached_tokens" in backend_dict
        
        # Multimodal fields should be in original usage format
        assert "original_usage.image_count" in backend_dict
        assert "original_usage.audio_seconds" in backend_dict
        assert "original_usage.video_seconds" in backend_dict
        
        # Values should match for standard tokens
        assert backend_dict["completion_tokens"] == backend_dict["original_usage.completion_tokens"]
        assert backend_dict["prompt_tokens"] == backend_dict["original_usage.prompt_tokens"]
        assert backend_dict["total_tokens"] == backend_dict["original_usage.total_tokens"]
        
        # Multimodal field values should be correct
        assert backend_dict["original_usage.image_count"] == 3
        assert backend_dict["original_usage.audio_seconds"] == 30
        assert backend_dict["original_usage.video_seconds"] == 90 