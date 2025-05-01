# Integration Mechanics

## Architecture Overview

The Opik Optimizer's integration with LiteLLM follows a layered architecture:

1. **Provider Layer**
   - Direct provider connections
   - Authentication handling
   - Request formatting

2. **Abstraction Layer**
   - Unified API interface
   - Response normalization
   - Error handling

3. **Optimization Layer**
   - Model-specific optimizations
   - Performance tuning
   - Resource management

## Integration Flow

### 1. Initialization
```python
from opik.optimizer import BaseOptimizer
from litellm import completion

class Optimizer(BaseOptimizer):
    def __init__(self, provider_config):
        self.provider = provider_config.provider
        self.model = provider_config.model
        self.client = completion
```

### 2. Request Processing
```python
def process_request(self, prompt, **kwargs):
    try:
        response = self.client(
            model=self.model,
            messages=[{"role": "user", "content": prompt}],
            **kwargs
        )
        return self.normalize_response(response)
    except Exception as e:
        return self.handle_error(e)
```

### 3. Response Normalization
```python
def normalize_response(self, response):
    return {
        "content": response.choices[0].message.content,
        "usage": response.usage,
        "model": response.model
    }
```

## Provider-Specific Handling

### OpenAI/Azure
```python
def handle_openai(self, request):
    return self.client(
        model=self.model,
        messages=request.messages,
        temperature=request.temperature,
        max_tokens=request.max_tokens
    )
```

### Anthropic
```python
def handle_anthropic(self, request):
    return self.client(
        model=self.model,
        prompt=request.prompt,
        temperature=request.temperature,
        max_tokens_to_sample=request.max_tokens
    )
```

### Google
```python
def handle_google(self, request):
    return self.client(
        model=self.model,
        contents=request.contents,
        generation_config={
            "temperature": request.temperature,
            "max_output_tokens": request.max_tokens
        }
    )
```

## Error Handling

### Retry Logic
```python
def handle_error(self, error, retries=3):
    if retries > 0:
        if isinstance(error, RateLimitError):
            time.sleep(1)
            return self.process_request(retries=retries-1)
        elif isinstance(error, AuthenticationError):
            self.refresh_credentials()
            return self.process_request(retries=retries-1)
    raise error
```

### Fallback Strategy
```python
def fallback_provider(self):
    providers = ["openai", "anthropic", "google"]
    current_index = providers.index(self.provider)
    next_provider = providers[(current_index + 1) % len(providers)]
    return self.initialize_provider(next_provider)
```

## Performance Optimization

### Caching
```python
from functools import lru_cache

@lru_cache(maxsize=1000)
def cached_completion(self, prompt, **kwargs):
    return self.process_request(prompt, **kwargs)
```

### Batch Processing
```python
def batch_process(self, prompts, batch_size=10):
    results = []
    for i in range(0, len(prompts), batch_size):
        batch = prompts[i:i+batch_size]
        batch_results = self.client(
            model=self.model,
            messages=[{"role": "user", "content": p} for p in batch]
        )
        results.extend(batch_results)
    return results
```

## Monitoring and Logging

### Metrics Collection
```python
def collect_metrics(self, response):
    return {
        "latency": response.latency,
        "tokens": response.usage.total_tokens,
        "cost": self.calculate_cost(response),
        "success": True
    }
```

### Logging
```python
def log_request(self, request, response, metrics):
    logger.info(
        f"Request to {self.provider}/{self.model}",
        extra={
            "request": request,
            "response": response,
            "metrics": metrics
        }
    )
```

## Best Practices

1. **Provider Management**
   - Implement proper error handling
   - Use appropriate retry strategies
   - Monitor provider status

2. **Performance**
   - Implement caching where appropriate
   - Use batch processing for efficiency
   - Monitor resource usage

3. **Security**
   - Secure credential storage
   - Implement proper access controls
   - Monitor for suspicious activity

4. **Maintenance**
   - Regular dependency updates
   - Provider-specific testing
   - Performance monitoring

## Next Steps

- Learn about [Provider-Specific Features](./07c-provider-features.md)
- Explore [Configuration Options](./05-configuration-and-usage.md)
- Check [Troubleshooting Guide](./07d-troubleshooting.md) 