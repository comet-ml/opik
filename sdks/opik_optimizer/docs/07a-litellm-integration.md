# LiteLLM Integration

## Overview

The Opik Optimizer leverages LiteLLM for seamless integration with multiple LLM providers. This integration provides a unified interface for accessing various language models while maintaining consistent behavior across different providers.

## Supported Providers

### OpenAI
- gpt-4
- gpt-3.5-turbo
- gpt-4-turbo-preview
- gpt-3.5-turbo-instruct

### Azure OpenAI
- gpt-4
- gpt-3.5-turbo
- gpt-4-turbo-preview

### Anthropic
- claude-3-opus
- claude-3-sonnet
- claude-2.1
- claude-2.0
- claude-instant-1.2

### Google
- gemini-pro
- gemini-1.0-pro
- palm-2

### Mistral
- mistral-tiny
- mistral-small
- mistral-medium

### Cohere
- command
- command-light
- command-nightly

## Key Features

1. **Unified Interface**
   - Consistent API across providers
   - Standardized response format
   - Simplified error handling

2. **Provider Abstraction**
   - Seamless switching between providers
   - Automatic fallback handling
   - Provider-specific optimizations

3. **Configuration Management**
   - Centralized provider settings
   - Environment-based configuration
   - Secure credential handling

4. **Performance Optimization**
   - Automatic retry mechanisms
   - Rate limit handling
   - Connection pooling

## Configuration

### Environment Variables
```bash
# OpenAI
OPENAI_API_KEY=your-key

# Azure OpenAI
AZURE_API_KEY=your-key
AZURE_API_BASE=your-endpoint

# Anthropic
ANTHROPIC_API_KEY=your-key

# Google
GOOGLE_API_KEY=your-key

# Mistral
MISTRAL_API_KEY=your-key

# Cohere
COHERE_API_KEY=your-key
```

### Provider Configuration
```python
from opik.config import ProviderConfig

config = ProviderConfig(
    provider="openai",  # or "azure", "anthropic", etc.
    model="gpt-4",
    api_key="your-key",
    api_base="your-endpoint",  # for Azure
    temperature=0.1,
    max_tokens=5000
)
```

## Best Practices

1. **Provider Selection**
   - Choose based on task requirements
   - Consider cost and performance
   - Evaluate model capabilities

2. **Configuration Management**
   - Use environment variables
   - Implement secure storage
   - Maintain separate configs for environments

3. **Error Handling**
   - Implement retry logic
   - Handle rate limits
   - Monitor provider status

4. **Performance Optimization**
   - Use appropriate batch sizes
   - Implement caching
   - Monitor usage metrics

## Next Steps

- Learn about [Integration Mechanics](./07b-integration-mechanics.md)
- Explore [Configuration Options](./05-configuration-and-usage.md)
- Check [Provider-Specific Features](./07c-provider-features.md) 