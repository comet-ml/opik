import pytest
from typing import Dict, Any
from opik.llm_usage.opik_usage import OpikUsage
from opik.llm_usage.openai_chat_completions_usage import OpenAICompletionsUsage


class TestOpikUsageMultimodal:
    """Test class for multimodal usage parsing functionality."""

    def test_opik_usage__from_openai_completions_dict__with_audio_tokens(self):
        """Test that audio tokens are correctly extracted from OpenAI completion details."""
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
        
        assert usage.completion_tokens == 100
        assert usage.prompt_tokens == 200
        assert usage.total_tokens == 300
        assert usage.audio_input_tokens == 10
        assert usage.audio_output_tokens == 20
        assert usage.image_count is None
        assert usage.video_seconds is None
        assert usage.audio_seconds is None

    def test_opik_usage__from_openai_completions_dict__with_multimodal_fields(self):
        """Test that multimodal fields are correctly handled."""
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
            "image_count": 2,
            "video_seconds": 30,
            "audio_seconds": 45,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        
        assert usage.audio_input_tokens == 10
        assert usage.audio_output_tokens == 20
        assert usage.image_count is None  # These are not extracted from token details
        assert usage.video_seconds is None
        assert usage.audio_seconds is None

    def test_opik_usage__from_openai_completions_dict__without_audio_tokens(self):
        """Test that missing audio tokens are handled gracefully."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        
        assert usage.completion_tokens == 100
        assert usage.prompt_tokens == 200
        assert usage.total_tokens == 300
        assert usage.audio_input_tokens is None
        assert usage.audio_output_tokens is None

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
        
        assert usage.audio_input_tokens is None
        assert usage.audio_output_tokens == 20

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
        
        assert usage.audio_input_tokens is None  # Zero is falsy, so None is returned
        assert usage.audio_output_tokens is None

    def test_opik_usage__to_backend_compatible_full_usage_dict__includes_multimodal_fields(self):
        """Test that multimodal fields are included in backend compatible dict."""
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
        
        # Manually set multimodal fields for testing
        usage.image_count = 2
        usage.video_seconds = 30
        usage.audio_seconds = 45
        
        full_dict = usage.to_backend_compatible_full_usage_dict()
        
        assert "completion_tokens" in full_dict
        assert "prompt_tokens" in full_dict
        assert "total_tokens" in full_dict
        assert "audio_input_tokens" in full_dict
        assert "audio_output_tokens" in full_dict
        assert "image_count" in full_dict
        assert "video_seconds" in full_dict
        assert "audio_seconds" in full_dict
        
        assert full_dict["completion_tokens"] == 100
        assert full_dict["prompt_tokens"] == 200
        assert full_dict["total_tokens"] == 300
        assert full_dict["audio_input_tokens"] == 10
        assert full_dict["audio_output_tokens"] == 20
        assert full_dict["image_count"] == 2
        assert full_dict["video_seconds"] == 30
        assert full_dict["audio_seconds"] == 45

    def test_opik_usage__to_backend_compatible_full_usage_dict__excludes_none_values(self):
        """Test that None values are excluded from backend compatible dict."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        
        full_dict = usage.to_backend_compatible_full_usage_dict()
        
        assert "completion_tokens" in full_dict
        assert "prompt_tokens" in full_dict
        assert "total_tokens" in full_dict
        assert "audio_input_tokens" not in full_dict
        assert "audio_output_tokens" not in full_dict
        assert "image_count" not in full_dict
        assert "video_seconds" not in full_dict
        assert "audio_seconds" not in full_dict

    def test_opik_usage__multimodal_fields__with_provider_usage(self):
        """Test that multimodal fields work with provider usage."""
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
        
        assert usage.audio_input_tokens == 10
        assert usage.audio_output_tokens == 20
        
        # Test that provider usage is correctly parsed
        assert isinstance(usage.provider_usage, OpenAICompletionsUsage)
        assert usage.provider_usage.completion_tokens == 100
        assert usage.provider_usage.prompt_tokens == 200
        assert usage.provider_usage.total_tokens == 300
        assert usage.provider_usage.completion_tokens_details.audio_tokens == 20
        assert usage.provider_usage.completion_tokens_details.reasoning_tokens == 30
        assert usage.provider_usage.prompt_tokens_details.audio_tokens == 10
        assert usage.provider_usage.prompt_tokens_details.cached_tokens == 15

    def test_opik_usage__multimodal_fields__invalid_data_types(self):
        """Test that invalid data types for multimodal fields are handled."""
        from pydantic import ValidationError
        
        # Test creating OpikUsage with invalid data types
        with pytest.raises(ValidationError):
            OpikUsage(
                audio_input_tokens="invalid",
                provider_usage=None
            )
        
        with pytest.raises(ValidationError):
            OpikUsage(
                image_count="invalid",
                provider_usage=None
            )

    def test_opik_usage__multimodal_fields__large_values(self):
        """Test that large values for multimodal fields are handled correctly."""
        usage_data = {
            "completion_tokens": 100,
            "prompt_tokens": 200,
            "total_tokens": 300,
            "completion_tokens_details": {
                "audio_tokens": 999999,
            },
            "prompt_tokens_details": {
                "audio_tokens": 999999,
            },
        }
        
        usage = OpikUsage.from_openai_completions_dict(usage_data)
        
        # Set large values
        usage.image_count = 999999
        usage.video_seconds = 999999
        usage.audio_seconds = 999999
        
        full_dict = usage.to_backend_compatible_full_usage_dict()
        
        assert full_dict["audio_input_tokens"] == 999999
        assert full_dict["audio_output_tokens"] == 999999
        assert full_dict["image_count"] == 999999
        assert full_dict["video_seconds"] == 999999
        assert full_dict["audio_seconds"] == 999999

    def test_opik_usage__multimodal_fields__explicit_creation(self):
        """Test creating OpikUsage with explicit multimodal fields."""
        from opik.llm_usage.unknown_usage import UnknownUsage
        
        provider_usage = UnknownUsage.from_original_usage_dict({})
        
        usage = OpikUsage(
            completion_tokens=100,
            prompt_tokens=200,
            total_tokens=300,
            audio_input_tokens=10,
            audio_output_tokens=20,
            image_count=2,
            video_seconds=30,
            audio_seconds=45,
            provider_usage=provider_usage
        )
        
        assert usage.completion_tokens == 100
        assert usage.prompt_tokens == 200
        assert usage.total_tokens == 300
        assert usage.audio_input_tokens == 10
        assert usage.audio_output_tokens == 20
        assert usage.image_count == 2
        assert usage.video_seconds == 30
        assert usage.audio_seconds == 45

    def test_opik_usage__multimodal_fields__backend_compatibility_with_original_usage(self):
        """Test that multimodal fields work correctly with original usage data."""
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
        usage.image_count = 2
        usage.video_seconds = 30
        usage.audio_seconds = 45
        
        full_dict = usage.to_backend_compatible_full_usage_dict()
        
        # Test that both standard and multimodal fields are present
        assert "completion_tokens" in full_dict
        assert "audio_input_tokens" in full_dict
        assert "image_count" in full_dict
        
        # Test that original usage is properly flattened
        assert "original_usage.completion_tokens" in full_dict
        assert "original_usage.prompt_tokens" in full_dict
        assert "original_usage.total_tokens" in full_dict
        assert "original_usage.completion_tokens_details.audio_tokens" in full_dict
        assert "original_usage.completion_tokens_details.reasoning_tokens" in full_dict
        assert "original_usage.prompt_tokens_details.audio_tokens" in full_dict
        assert "original_usage.prompt_tokens_details.cached_tokens" in full_dict
        
        # Test values
        assert full_dict["original_usage.completion_tokens"] == 100
        assert full_dict["original_usage.completion_tokens_details.audio_tokens"] == 20
        assert full_dict["original_usage.completion_tokens_details.reasoning_tokens"] == 30
        assert full_dict["original_usage.prompt_tokens_details.audio_tokens"] == 10
        assert full_dict["original_usage.prompt_tokens_details.cached_tokens"] == 15