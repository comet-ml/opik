# Opik Config Service

Dynamic configuration management for AI agents with backend sync and experimentation support.

## Quick Start

### Start the config service

```bash
cd apps/opik-config
uv run python -m opik_config
```

The service runs on `http://localhost:5050`.

### Use in your agent

```python
from dataclasses import dataclass
from opik_config import agent_config, experiment_context, Prompt

@agent_config
@dataclass
class AgentConfig:
    temperature: float = 0.7
    max_tokens: int = 500
    system_prompt: Prompt = Prompt(
        name="default",
        prompt="You are a helpful assistant."
    )

config = AgentConfig()

# In your agent endpoint
@opik.track
def run_agent(request):
    with experiment_context(request):  # Extracts X-Opik-Experiment-Id header
        temp = config.temperature  # Gets override if experiment active
        # ... rest of agent logic
```

## API Endpoints

- `GET /health` - Health check
- `POST /config/get` - Get config values
- `POST /config/set` - Set a config value
- `GET /config/list` - List all config values

## Experiments

Create experiments via the UI or API:

```bash
# Set an experiment override
curl -X POST http://localhost:5050/config/set \
  -H "Content-Type: application/json" \
  -d '{"key": "temperature", "value": 0.9, "experiment_id": "exp-123"}'
```

Then call your agent with the experiment header:

```bash
curl http://your-agent/endpoint \
  -H "X-Opik-Experiment-Id: exp-123"
```

## Demo Notebook

A sample LangGraph agent demonstrating the config feature:

```bash
# Install demo dependencies
cd apps/opik-config
uv pip install -e ".[demo]"

# Start the config backend (in one terminal)
uv run python -m opik_config

# Run Jupyter (in another terminal)
uv run jupyter notebook demo_agent.ipynb
```

The demo shows:
- Defining config with `@agent_config` decorator
- Routing based on config values
- Creating experiments with override values
- Running with `experiment_context` to use overrides
