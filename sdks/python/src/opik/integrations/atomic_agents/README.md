# Opik × Atomic Agents Integration

> **Status:** Beta – This integration is functional but incomplete. It currently traces agent runs and tool calls, but not LLM invocations.

`opik` now ships first-class support for [Atomic Agents](https://github.com/BrainBlend-AI/atomic-agents), a lightweight modular framework for building agentic pipelines.

---

## Why combine Opik & Atomic Agents?

| Challenge                                    | How the integration helps                                    |
|----------------------------------------------|--------------------------------------------------------------|
| Limited visibility into agent chains         | Automatic traces & spans for agents and their tools.       |
| Hard to debug prompt & tool interactions     | Structured inputs/outputs are captured as JSON.                 |
| No easy way to compare agent revisions       | Opik dashboards let you diff runs by latency, cost & quality. |

---

## Installation

You need to have both `opik` and `atomic-agents` installed.

```bash
# 1. Install Opik in editable mode from the root of this repository,
#    including the [atomic-agents] extra to pull in dependencies.
pip install -e "sdks/python[atomic-agents]"

# 2. Install Atomic Agents
pip install atomic-agents
```

---

## Quick-start

The following example demonstrates how to trace a simple agent with a tool. You can find a runnable version of this script at `sdks/python/examples/atomic_agents_quickstart.py`.

```python
import os
from atomic_agents.agents.base_agent import BaseChatAgent, BaseChatAgentConfig
from atomic_agents.tools.base_tool import BaseTool
from pydantic import BaseModel, Field

from opik.integrations.atomic_agents import track_atomic_agents

# Step 1 – Enable auto-tracking once at your application's start.
# Make sure your OPIK_API_KEY is set as an environment variable.
track_atomic_agents(project_name="atomic-agents-example")


# Step 2 - Define a tool
class GreetingToolInput(BaseModel):
    name: str = Field(..., description="The name of the person to greet.")

class GreetingTool(BaseTool):
    name: str = "greeting_tool"
    description: str = "A tool that returns a greeting."
    input_schema = GreetingToolInput

    def run(self, params: GreetingToolInput) -> str:
        return f"Hello, {params.name}!"

# Step 3 – Build your agent and register the tool
class EchoAgent(BaseChatAgent):
    def get_prompt(self, chat_history, user_input):
        return f"You are a helpful assistant. Use your tools. The user said: {user_input}"

agent = EchoAgent(
    config=BaseChatAgentConfig(
        model=os.getenv("OPIK_TEST_MODEL", "gpt-3.5-turbo"),
        tools=[GreetingTool()]
    )
)

# Step 4 – Run the agent
# This will create a trace in Opik with a root span for the agent
# and a child span for the tool call.
if __name__ == "__main__":
    agent.run("Use the greeting tool to greet John.")

```

Running the script will emit a trace to your Opik instance. You will be able to visualize the full execution, including the agent's run and the tool call, in the Opik UI.

---

## Advanced – Context Providers

You can inject the current Opik trace ID into your system prompts for cross-system correlation.

```python
from opik.integrations.atomic_agents import OpikContextProvider

provider = OpikContextProvider(project_name="my-demo-project")
provider.set_trace_id("<trace-id>")
agent.register_context_provider("opik", provider)
```

The provider renders the following context string:
```
[Opik Trace Info]
trace_id: <trace-id>
project:  my-demo-project
```

---

## FAQ & Troubleshooting
**Q.** My project uses an old Atomic Agents version.<br/>
**A.** The integration relies on patching `BaseAgent.run` and `BaseTool.run`. As long as your version uses these methods, it should work. If you encounter issues, please open an issue in the Opik repository.

**Q.** How do I disable tracing in production?  <br/>
**A.** Set the environment variable `OPIK_TRACK_DISABLE=1`.

**Q.** Where are Pydantic schemas stored?  <br/>
**A.** They are stored in the trace's metadata under the keys `atomic_input_schema` and `atomic_output_schema`.
