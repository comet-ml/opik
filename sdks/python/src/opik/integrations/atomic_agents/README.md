# Opik × Atomic Agents Integration

> **Status:** Beta – Phase-1 (root trace + schema capture)

`opik` now ships first-class support for [Atomic Agents](https://github.com/BrainBlend-AI/atomic-agents), a lightweight modular framework for building agentic pipelines.

---

## Why combine Opik & Atomic Agents?

| Challenge                                    | How the integration helps                                    |
|----------------------------------------------|--------------------------------------------------------------|
| Limited visibility into agent chains         | Automatic traces & spans with token‐level usage + cost       |
| Hard to debug prompt & tool interactions     | Structured inputs / outputs captured as JSON                 |
| No easy way to compare agent revisions       | Opik dashboards let you diff runs by latency, cost & quality |

---

## Installation
```bash
# Atomic Agents + Opik extras
pip install "opik[atomic_agents]" atomic-agents
```
*(extras install the `pydantic` & tracing dependencies automatically)*

---

## Quick-start
See `sdks/python/examples/atomic_agents_quickstart.py` for a runnable version.

```python
from atomic_agents.agents.base_agent import BaseChatAgent, BaseChatAgentConfig
from pydantic import BaseModel, Field

from opik.integrations.atomic_agents import track_atomic_agents

# Step 1 – enable auto-tracking once at app start
track_atomic_agents(project_name="my-demo-project")

# Step 2 – build your agent
class Input(BaseModel):
    chat_message: str = Field(...)

class Output(BaseModel):
    chat_message: str

class EchoAgent(BaseChatAgent):
    input_schema = Input
    output_schema = Output

    def run(self, inp: Input) -> Output:  # type: ignore[override]
        return Output(chat_message=inp.chat_message)

agent = EchoAgent(
    config=BaseChatAgentConfig(model="gpt-3.5-turbo")
)

# Step 3 – run ✨
print(agent.run(Input(chat_message="Hello!")))
```
Running the script will emit a trace visible in the Opik UI with **input / output schemas already attached**.

---

## Advanced – Context Providers

Inject the current Opik trace id into your system prompts:

```python
from opik.integrations.atomic_agents import OpikContextProvider

provider = OpikContextProvider(project_name="my-demo-project")
provider.set_trace_id("<trace-id>")
agent.register_context_provider("opik", provider)
```

The provider renders:
```
[Opik Trace Info]
trace_id: <trace-id>
project:  my-demo-project
```

---

## FAQ & Troubleshooting
**Q.** My project uses an old Atomic Agents version.<br/>
**A.** The integration relies only on `BaseAgent.run`; older versions should still work. If you notice breakage, open an issue.

**Q.** How do I disable tracing in prod?  → Set env `OPIK_TRACK_DISABLE=1`.

**Q.** Where are Pydantic schemas stored?  → In trace metadata keys `atomic_input_schema` / `atomic_output_schema`.

---

Made with ❤️ by the Opik team. 