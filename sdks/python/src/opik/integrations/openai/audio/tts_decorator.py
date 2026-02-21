"""
OpenAI Text-to-Speech (TTS) tracking decorator for Opik.

This module provides tracking support for OpenAI's audio.speech.create API
including both standard and streaming responses.
"""

from typing import Any, Callable, Dict, Optional
import functools
import logging

import opik
from opik.api_objects import span

LOGGER = logging.getLogger(__name__)

# TTS pricing per 1M characters (as of 2024)
# https://openai.com/pricing
TTS_MODEL_COSTS = {
    "tts-1": 15.0,  # $15 per 1M characters
    "tts-1-hd": 30.0,  # $30 per 1M characters
}

DEFAULT_COST_PER_MILLION_CHARS = 15.0


def _calculate_tts_cost(model: str, input_text: str) -> float:
    """Calculate the cost of a TTS request based on model and input length."""
    cost_per_million = TTS_MODEL_COSTS.get(model, DEFAULT_COST_PER_MILLION_CHARS)
    char_count = len(input_text)
    return (char_count / 1_000_000) * cost_per_million


class TTSTrackDecorator:
    """Decorator factory for tracking OpenAI TTS API calls."""

    def __init__(self, provider: str = "openai"):
        self.provider = provider

    def track(
        self,
        type: str = "general",
        name: str = "audio.speech.create",
        project_name: Optional[str] = None,
    ) -> Callable:
        """
        Create a decorator for tracking TTS API calls.

        Args:
            type: The type of operation (default: "general")
            name: The name for the span (default: "audio.speech.create")
            project_name: Optional project name for tracking

        Returns:
            A decorator function
        """

        def decorator(func: Callable) -> Callable:
            @functools.wraps(func)
            def wrapper(*args: Any, **kwargs: Any) -> Any:
                if not opik.is_tracing_active():
                    return func(*args, **kwargs)

                # Extract parameters
                model = kwargs.get("model", "tts-1")
                input_text = kwargs.get("input", "")
                voice = kwargs.get("voice", "alloy")
                response_format = kwargs.get("response_format", "mp3")
                speed = kwargs.get("speed", 1.0)

                # Calculate usage metrics
                char_count = len(input_text)
                cost = _calculate_tts_cost(model, input_text)

                # Build metadata
                metadata = {
                    "created_from": "openai",
                    "type": "openai_tts",
                    "provider": self.provider,
                    "model": model,
                    "voice": voice,
                    "response_format": response_format,
                    "speed": speed,
                }

                # Build usage info
                usage = {
                    "input_characters": char_count,
                    "estimated_cost_usd": cost,
                }

                tags = ["openai", "tts", model]

                # Create tracked function with opik.track
                tracked_func = opik.track(
                    name=name,
                    type=type,
                    tags=tags,
                    metadata=metadata,
                    project_name=project_name,
                )(func)

                # Execute and get result
                result = tracked_func(*args, **kwargs)

                # Try to add usage info to current span
                try:
                    current_span = opik.get_current_span()
                    if current_span:
                        current_span.update(usage=usage)
                except Exception as e:
                    LOGGER.debug(f"Could not update span with usage info: {e}")

                return result

            return wrapper

        return decorator


class TTSStreamingTrackDecorator:
    """Decorator factory for tracking OpenAI TTS streaming API calls."""

    def __init__(self, provider: str = "openai"):
        self.provider = provider

    def track(
        self,
        type: str = "general",
        name: str = "audio.speech.with_streaming_response.create",
        project_name: Optional[str] = None,
    ) -> Callable:
        """
        Create a decorator for tracking TTS streaming API calls.

        Args:
            type: The type of operation (default: "general")
            name: The name for the span
            project_name: Optional project name for tracking

        Returns:
            A decorator function
        """

        def decorator(func: Callable) -> Callable:
            @functools.wraps(func)
            def wrapper(*args: Any, **kwargs: Any) -> Any:
                if not opik.is_tracing_active():
                    return func(*args, **kwargs)

                # Extract parameters
                model = kwargs.get("model", "tts-1")
                input_text = kwargs.get("input", "")
                voice = kwargs.get("voice", "alloy")
                response_format = kwargs.get("response_format", "mp3")
                speed = kwargs.get("speed", 1.0)

                # Calculate usage metrics
                char_count = len(input_text)
                cost = _calculate_tts_cost(model, input_text)

                # Build metadata
                metadata = {
                    "created_from": "openai",
                    "type": "openai_tts_streaming",
                    "provider": self.provider,
                    "model": model,
                    "voice": voice,
                    "response_format": response_format,
                    "speed": speed,
                }

                # Build usage info
                usage = {
                    "input_characters": char_count,
                    "estimated_cost_usd": cost,
                }

                tags = ["openai", "tts", "streaming", model]

                # Create tracked function with opik.track
                tracked_func = opik.track(
                    name=name,
                    type=type,
                    tags=tags,
                    metadata=metadata,
                    project_name=project_name,
                )(func)

                # Execute and get result
                result = tracked_func(*args, **kwargs)

                # Try to add usage info to current span
                try:
                    current_span = opik.get_current_span()
                    if current_span:
                        current_span.update(usage=usage)
                except Exception as e:
                    LOGGER.debug(f"Could not update span with usage info: {e}")

                return result

            return wrapper

        return decorator
