"""
Model default parameters utilities.

This module provides utilities for determining model capabilities and
retrieving default parameter values for different LLM providers.
"""

from typing import Dict, Any, Optional


# OpenAI reasoning models that require special handling
REASONING_MODELS = [
    "o1",
    "o1-mini",
    "o1-pro",
    "o1-preview",
    "o3",
    "o3-mini",
    "o3-pro",
    "o4-mini",
    "gpt-5",
    "gpt-5-mini",
    "gpt-5-nano",
    "gpt-5.1",
    "gpt-5.2",
]


def is_reasoning_model(model: Optional[str]) -> bool:
    """
    Check if the given model is an OpenAI reasoning model.
    
    Reasoning models (O1, O3, O4-mini, GPT-5 family) have special requirements:
    - temperature is fixed at 1.0
    - reasoningEffort parameter is available
    
    Args:
        model: The model name to check
        
    Returns:
        True if the model is a reasoning model, False otherwise
    """
    if not model:
        return False
    
    model_lower = model.lower()
    return any(model_lower.startswith(rm) for rm in REASONING_MODELS)


def supports_gemini_thinking_level(model: Optional[str]) -> bool:
    """
    Check if the given Gemini model supports thinking level parameter.
    
    Args:
        model: The model name to check
        
    Returns:
        True if the model supports thinking level, False otherwise
    """
    if not model:
        return False
    
    model_lower = model.lower()
    return "gemini-3-pro" in model_lower or "gemini-3-flash" in model_lower


def supports_vertex_ai_thinking_level(model: Optional[str]) -> bool:
    """
    Check if the given Vertex AI model supports thinking level parameter.
    
    Args:
        model: The model name to check
        
    Returns:
        True if the model supports thinking level, False otherwise
    """
    if not model:
        return False
    
    model_lower = model.lower()
    return "gemini-3-pro" in model_lower


def get_openai_default_params(model: Optional[str], kwargs: Dict[str, Any]) -> Dict[str, Any]:
    """
    Get default parameters for OpenAI models that are not present in kwargs.
    
    Args:
        model: The OpenAI model name
        kwargs: The parameters that were explicitly passed
        
    Returns:
        Dictionary of default parameters to add
    """
    defaults = {}
    
    # Standard OpenAI parameters (apply to all models)
    if "temperature" not in kwargs:
        defaults["temperature"] = 1.0
    if "top_p" not in kwargs:
        defaults["top_p"] = 1.0
    if "frequency_penalty" not in kwargs:
        defaults["frequency_penalty"] = 0.0
    if "presence_penalty" not in kwargs:
        defaults["presence_penalty"] = 0.0
    
    # Reasoning-specific parameters
    if is_reasoning_model(model):
        if "reasoning_effort" not in kwargs:
            defaults["reasoning_effort"] = "medium"
    
    return defaults


def get_gemini_default_params(model: Optional[str], kwargs: Dict[str, Any]) -> Dict[str, Any]:
    """
    Get default parameters for Google Gemini models (google_ai provider).
    
    Supported parameters: temperature, top_p, max_output_tokens, thinking_level
    Does NOT support: frequency_penalty, presence_penalty
    
    Args:
        model: The Gemini model name
        kwargs: The parameters that were explicitly passed
        
    Returns:
        Dictionary of default parameters to add
    """
    defaults = {}
    
    # Gemini API defaults (from Google AI documentation)
    if "temperature" not in kwargs:
        defaults["temperature"] = 1.0
    if "top_p" not in kwargs:
        defaults["top_p"] = 0.95
    
    # Gemini 3 models support thinking level
    if supports_gemini_thinking_level(model):
        if "thinking_level" not in kwargs:
            defaults["thinking_level"] = "high"
    
    return defaults


def get_vertex_ai_default_params(model: Optional[str], kwargs: Dict[str, Any]) -> Dict[str, Any]:
    """
    Get default parameters for Vertex AI models (google_vertexai provider).
    
    Supported parameters: temperature, top_p, max_output_tokens, thinking_level
    Does NOT support: frequency_penalty, presence_penalty
    
    Args:
        model: The Vertex AI model name
        kwargs: The parameters that were explicitly passed
        
    Returns:
        Dictionary of default parameters to add
    """
    defaults = {}
    
    # Vertex AI defaults (same as Gemini)
    if "temperature" not in kwargs:
        defaults["temperature"] = 1.0
    if "top_p" not in kwargs:
        defaults["top_p"] = 0.95
    
    # Vertex AI Gemini 3 Pro supports thinking level
    if supports_vertex_ai_thinking_level(model):
        if "thinking_level" not in kwargs:
            defaults["thinking_level"] = "low"
    
    return defaults


def get_anthropic_default_params(model: Optional[str], kwargs: Dict[str, Any]) -> Dict[str, Any]:
    """
    Get default parameters for Anthropic models.
    
    Supported parameters: temperature, top_p, max_tokens
    Does NOT support: frequency_penalty, presence_penalty
    
    Args:
        model: The Anthropic model name
        kwargs: The parameters that were explicitly passed
        
    Returns:
        Dictionary of default parameters to add
    """
    defaults = {}
    
    # Anthropic API defaults
    if "temperature" not in kwargs:
        defaults["temperature"] = 1.0
    if "top_p" not in kwargs:
        defaults["top_p"] = 1.0
    
    return defaults
